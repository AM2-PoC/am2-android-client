package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.concurrent.thread

object AudioRecorder {
    private const val TAG = "AudioRecorder"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val FRAME_SIZE = 320 // 20ms frame untuk 16kHz

    /*
     * How long a restart waits for the previous recording to unwind.
     *
     * The thread leaves its loop at the next frame boundary, so this only has
     * to outlast one blocking read plus teardown. It is a ceiling on a wait
     * that normally does not happen, not a delay every press pays.
     */
    private const val RECORDER_DRAIN_TIMEOUT_MS = 250L
    private val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    /*
     * How much speech may be held while the relay decides.
     *
     * Fifteen frames is 300 ms, comfortably past the measured round trip. Past
     * that the operator has been talking into a transmission that was never
     * authorized, and replaying it late would be worse than losing it.
     */
    private const val VOX_PREROLL_FRAMES = 15

    /*
     * The word that triggered VOX.
     *
     * Frames reach the encoder only while isTalkingNow(). In push-to-talk that
     * is right -- the operator pressed, so nothing before the press was meant
     * to be sent. In VOX it is the whole complaint: VOX is triggered *by* the
     * onset of speech, so by the time talking is true the syllable that crossed
     * the threshold has already been read, measured for its amplitude, and
     * dropped on the floor.
     *
     * VOX_PREROLL_FRAMES above does not cover it. That holds frames between
     * "talking started" and "the relay authorized" -- a later window entirely.
     * Everything before the trigger was never captured at all.
     *
     * So frames are kept before there is any reason to keep them, and handed to
     * the encoder in order when the reason arrives. Fifteen frames is 300 ms;
     * the cost is under ten kilobytes of 16-bit mono.
     */
    private const val VOX_PRETRIGGER_FRAMES = 15

    /*
     * Restart pacing after the microphone is lost.
     *
     * A microphone held by a phone call refuses every source immediately, so
     * an unpaced retry is a tight loop of service intents. The delay doubles
     * per attempt and stops at half a minute; a single good frame resets it.
     */
    private const val VOX_RESTART_BASE_MS = 1000L
    private const val VOX_RESTART_MAX_MS = 30000L
    private const val VOX_RESTART_MAX_DOUBLINGS = 5

    /*
     * The floor between two VOX triggers.
     *
     * The threshold is crossed by a frame, and frames arrive every 20 ms. What
     * decides whether to transmit is the onset of speech, which happens once.
     */
    private const val VOX_TRIGGER_INTERVAL_MS = 500L


    @Volatile
    private var audioRecord: AudioRecord? = null

    /*
     * The processing the platform was only ever asked for indirectly.
     *
     * VOICE_COMMUNICATION is a request, and on many handsets it is honoured
     * with gain control, noise suppression and echo cancellation. On many
     * others it is not, and nothing here ever asked directly -- which is the
     * shape of the report: audio arriving quiet on *some* devices, VOX deaf on
     * *some* devices. Both follow from a capture level nobody set.
     *
     * AudioFilter cannot fix it. It multiplies by 1.0, 1.0 and 0.8 -- unity,
     * with the treble pulled down -- and its own comment records why: a fixed
     * boost was there and came out because it clipped. A fixed boost is
     * precisely what cannot serve a loud handset and a quiet one at once. Gain
     * that follows the signal can.
     *
     * Held so they can be released with the recorder: each holds a native
     * session, and VOX restarts every time a phone call takes the microphone.
     */

    /*
     * Held so a restart can wait for it.
     *
     * stopRecording only ever set a flag, and the thread was usually still
     * blocked inside AudioRecord.read(). A restart in that window recreated the
     * shared encoder under the old thread and published a new AudioRecord that
     * the old thread's cleanup then released — leaving the new transmission
     * with nothing to read from. It sent zero frames while the UI showed TX,
     * and nothing threw or logged.
     */
    @Volatile
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false
    private val opusCodec = OpusCodec()

    @Volatile
    var voxEnabled = false
    @Volatile
    var gatewayModeEnabled = false

    @Volatile
    private var voxThreshold = VoxSensitivity.DEFAULT_THRESHOLD
    private var voxSilenceTimer = 0L
    private const val VOX_SILENCE_TIMEOUT = 1500L
    private var voxTriggerCount = 0
    private const val VOX_TRIGGER_REQUIRED = 1

    /*
     * Why a frame loud enough to key did not key.
     *
     * Four guards can refuse, and none of them said which. The level report is
     * a peak over three seconds, so it can show that speech cleared the
     * threshold while nothing transmitted and still not say whether the channel
     * was held, a tone was playing, or the re-key interval had not elapsed.
     * Those have three different fixes and the aggregate picks none of them.
     *
     * Counted only for frames above the threshold: a quiet frame being refused
     * is not a fault, it is silence.
     */
    private const val BLOCK_OTHERS = 0
    private const val BLOCK_PLAYBACK = 1
    private const val BLOCK_TONE = 2
    private const val BLOCK_INTERVAL = 3
    private val voxBlocks = IntArray(4)
    private var lastVoxTriggerAt = 0L

    @Volatile
    private var voxRestartAttempts = 0

    /*
     * Speech encoded before the relay had authorized it.
     *
     * Touched only from the recording thread. In push-to-talk it stays empty:
     * capture is armed after authorization, so there is never anything to
     * hold. VOX is why it exists — see holdOrSend.
     */
    private val preRoll = ArrayDeque<ByteArray>()

    /** Raw PCM held before VOX has decided anything. See VOX_PRETRIGGER_FRAMES. */
    private val preTrigger = ArrayDeque<ShortArray>()

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * How loud speech has to be before VOX transmits.
     *
     * A room is not a constant. The value that keys on a normal voice in an
     * office is deaf in a vehicle and permanently triggered in a workshop, so
     * this is the one control a VOX radio always exposes.
     */
    fun setVoxThreshold(threshold: Int) {
        voxThreshold = threshold.coerceIn(VoxSensitivity.MIN_THRESHOLD, VoxSensitivity.MAX_THRESHOLD)
    }

    private class AudioFilter(sampleRate: Int) {
        private var lowState = 0f
        private var midState = 0f
        private val alphaLow = (2.0 * Math.PI * 400.0 / (sampleRate + 2.0 * Math.PI * 400.0)).toFloat()
        private val alphaMid = (2.0 * Math.PI * 2500.0 / (sampleRate + 2.0 * Math.PI * 2500.0)).toFloat()

        fun apply(buffer: ShortArray, length: Int) {
            for (i in 0 until length) {
                val sample = buffer[i].toFloat()
                lowState += alphaLow * (sample - lowState)
                val bass = lowState
                midState += alphaMid * (sample - midState)
                val mid = midState - bass
                val treble = sample - midState

                // Gain dikurangi ke normal (1.0x) agar tidak terlalu keras/pecah
                val processed = (bass * 1.0f) + (mid * 1.0f) + (treble * 0.8f)
                buffer[i] = processed.coerceIn(-32000f, 31000f).toInt().toShort()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return

        val context = appContext ?: return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            SafeLog.e(TAG, "Missing RECORD_AUDIO permission")
            return
        }

        val userIdStr = WebSocketManager.myUserId ?: "0"
        val userIdTruncated = userIdStr.toLongOrNull()?.toInt() ?: userIdStr.hashCode()

        /*
         * Wait for the previous recording to finish before touching anything it
         * shares. A flag cannot express this: the old thread is inside a
         * blocking read and has not reached its cleanup yet.
         */
        recordingThread?.let { previous ->
            if (previous.isAlive) {
                try {
                    previous.join(RECORDER_DRAIN_TIMEOUT_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                if (previous.isAlive) {
                    SafeLog.e(TAG, "Previous recording did not finish; refusing to start another")
                    return
                }
            }
        }
        recordingThread = null

        try {
            opusCodec.createEncoder(SAMPLE_RATE, 32000, 5)
            isRecording = true
            preRoll.clear()
            preTrigger.clear()

            recordingThread = thread(priority = Thread.MAX_PRIORITY, name = "AudioRecordThread") {
                try {
                    var success = false
                    /*
                     * VOICE_COMMUNICATION first, always.
                     *
                     * It is the source that asks the platform for echo
                     * cancellation, and this preference used to be keyed off
                     * the *output* route: a headset got it, and the built-in
                     * loudspeaker — the one route with an acoustic path back
                     * into this microphone — got raw MIC instead. In VOX, where
                     * the microphone stays open while the radio is talking,
                     * that closed a loop through the room.
                     *
                     * MIC remains as the fallback for a device that refuses.
                     */
                    val sources = arrayOf(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.MIC,
                    )
                    for (source in sources) {
                        if (success) break
                        try {
                            cleanupAudioRecord()
                            val recorder = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, (MIN_BUFFER_SIZE * 4).coerceAtLeast(FRAME_SIZE * 20))
                            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                                recorder.startRecording()
                                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                    audioRecord = recorder
                                    success = true
                                    WebSocketManager.currentTransmitTraceId()?.let {
                                        PttTrace.emit(event = "capture_started", traceId = it)
                                    }
                                } else recorder.release()
                            } else recorder.release()
                        } catch (e: Exception) {
                            SafeLog.e(TAG, "Failed to initialize AudioRecord", e)
                        }
                    }

                    if (!success) {
                        /*
                         * Every source refused. That is what a Bluetooth route
                         * in the wrong state looks like, and it used to end
                         * here: the thread fell through to `finally`, the
                         * talking state it was started for was never cleared,
                         * and the UI held TX while no frame was ever sent. The
                         * operator was told nothing -- the failure that looks
                         * exactly like working.
                         */
                        SafeLog.e(TAG, "No audio source could be opened; capture refused")
                        WebSocketManager.onCaptureFailed()
                    }

                    if (success && isRecording) {
                        val pcmBuffer = ShortArray(FRAME_SIZE)
                        val audioFilter = AudioFilter(SAMPLE_RATE)
                        while (isRecording) {
                            val recorder = audioRecord ?: break
                            val read = try {
                                recorder.read(pcmBuffer, 0, FRAME_SIZE)
                            } catch (e: Exception) { -1 }

                            if (read == FRAME_SIZE) {
                                // Capture is working, so whatever went wrong
                                // before is over and the next failure starts
                                // its backoff from the beginning.
                                if (voxRestartAttempts != 0) voxRestartAttempts = 0

                                var maxAmplitude = 0
                                for (i in 0 until read) {
                                    val abs = Math.abs(pcmBuffer[i].toInt())
                                    if (abs > maxAmplitude) maxAmplitude = abs
                                }
                                handleVoxLogic(maxAmplitude)
                                reportVoxLevel(maxAmplitude)

                                // Gunakan isTalkingNow() untuk respons yang lebih cepat (tanpa delay LiveData)
                                val talking = WebSocketManager.isTalkingNow()
                                if (talking) {
                                    // In order and before the live frame, so
                                    // the transmission opens on the syllable
                                    // that caused it rather than on whatever
                                    // followed it.
                                    flushPreTrigger(audioFilter, userIdTruncated)
                                    audioFilter.apply(pcmBuffer, read)
                                    val encodedData = opusCodec.encode(pcmBuffer, FRAME_SIZE)
                                    if (encodedData != null && isRecording) {
                                        // Read per frame: in VOX and gateway
                                        // mode this thread outlives a single
                                        // transmission, so an id captured once
                                        // would label every later frame with
                                        // the first transmission's id.
                                        WebSocketManager.currentTransmitTraceId()?.let {
                                            PttTrace.emit(
                                                event = "frame_encoded",
                                                traceId = it,
                                                frameBytes = encodedData.size,
                                            )
                                        }
                                        val packet = ByteBuffer.allocate(5 + encodedData.size)
                                            .order(ByteOrder.LITTLE_ENDIAN)
                                            .put(1.toByte())
                                            .putInt(userIdTruncated)
                                            .put(encodedData)
                                            .array()
                                        holdOrSend(packet)
                                    }
                                } else {
                                    if (preRoll.isNotEmpty()) {
                                        // The transmission ended without ever
                                        // being authorized. Nothing left to
                                        // flush to.
                                        preRoll.clear()
                                    }
                                    if (voxEnabled) {
                                        // A copy: pcmBuffer is read into every
                                        // iteration, so holding the array
                                        // itself would leave a ring of fifteen
                                        // references to the latest frame.
                                        preTrigger.addLast(pcmBuffer.copyOf(read))
                                        while (preTrigger.size > VOX_PRETRIGGER_FRAMES) {
                                            preTrigger.removeFirst()
                                        }
                                    }
                                }
                            } else if (read < 0) {
                                break
                            }

                            // Jika VOX tidak aktif, Gateway tidak aktif, dan tidak sedang berbicara, keluar dari loop
                            if (!voxEnabled && !gatewayModeEnabled && !WebSocketManager.isTalkingNow()) {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    SafeLog.e(TAG, "Recording loop failed", e)
                } finally {
                    /*
                     * isRecording is still set when the loop left on its own: a
                     * negative read, a source that never opened, an exception.
                     * Only stopRecording clears it. That is the whole
                     * difference between a stop somebody asked for and a
                     * microphone that was taken away -- by an incoming call,
                     * usually -- and until this was read, the second case ended
                     * VOX permanently: the thread is all of VOX, nothing
                     * restarted it, and checkVoxState runs on service start, a
                     * settings toggle and a socket reconnect, none of which a
                     * lost microphone causes.
                     */
                    val unrequested = isRecording
                    isRecording = false
                    cleanup()
                    if (unrequested) requestVoxRestart()
                }
            }
        } catch (e: Exception) {
            WebSocketManager.currentTransmitTraceId()?.let {
                PttTrace.emit(event = "recorder_start_failed", traceId = it)
            }
            SafeLog.e(TAG, "Failed to start recording", e)
            isRecording = false
        }
    }

    /**
     * Send the frame, or hold it until the relay says it may be sent.
     *
     * The relay sets `is_rx_only` for the length of its authorization lookup
     * and drops every audio frame that arrives while it is set. A press never
     * meets that window — capture is armed only once the acknowledgement is in
     * — but VOX cannot use that gate, because the microphone is already open
     * and startRecording returns at its first line. Frames therefore start
     * flowing the moment talking begins, which is before `ptt_audio_start` has
     * even reached the socket.
     *
     * The result was not a clipped opening but a holed one: frames pass, a
     * lookup's worth are dropped, frames pass again. VOX trips on the onset of
     * speech, so what fell in the hole was the first word.
     *
     * Delaying capture would only move the clip. These frames are good — they
     * simply cannot be sent yet — so they are held and flushed on
     * authorization.
     */
    private fun holdOrSend(packet: ByteArray) {
        if (WebSocketManager.isTransmitAuthorized()) {
            flushPreRoll()
            WebSocketManager.sendBinary(packet)
            return
        }
        preRoll.addLast(packet)
        while (preRoll.size > VOX_PREROLL_FRAMES) preRoll.removeFirst()
    }

    /**
     * Ask this device for the capture processing it has, and say what it gave.
     *
     * Availability is per device and per effect, so isAvailable() decides and
     * a create() that returns null or throws is simply an effect this handset
     * does not have. None of it is fatal: capture without them is what every
     * build before this one did.
     *
     * The line it logs is the point. "Sebagian device" cannot be answered by
     * reading source, and this is the only place that knows.
     */
    private fun flushPreTrigger(filter: AudioFilter, userIdTruncated: Int) {
        while (preTrigger.isNotEmpty()) {
            val pcm = preTrigger.removeFirst()
            filter.apply(pcm, pcm.size)
            val encoded = opusCodec.encode(pcm, FRAME_SIZE) ?: continue
            holdOrSend(
                ByteBuffer.allocate(5 + encoded.size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(1.toByte())
                    .putInt(userIdTruncated)
                    .put(encoded)
                    .array()
            )
        }
    }

    private fun flushPreRoll() {
        while (preRoll.isNotEmpty()) {
            WebSocketManager.sendBinary(preRoll.removeFirst())
        }
    }

    /*
     * What VOX actually measured, once a second, while it is listening.
     *
     * Three rounds of this were argued from source because nothing ever
     * recorded the one number that decides it: the amplitude seen, against the
     * threshold it was compared with. A field report of "not sensitive" and a
     * microphone returning near-silence look identical from here, and only this
     * line tells them apart.
     *
     * Only while VOX is armed and not transmitting -- that is the window the
     * complaint is about -- and only the window's peak, so a line a second says
     * what a hundred would.
     */
    /** Often enough to see a sentence, rare enough to be free. */
    private const val VOX_LEVEL_REPORT_MS = 3000L

    private var voxLevelPeak = 0
    private var voxLevelReportedAt = 0L

    /*
     * The sustained level, not only the loudest instant.
     *
     * Every sample the field has returned carries threshold=500, which is
     * MIN_THRESHOLD -- the slider at 100 of 100. The operator has run out of
     * travel and the radio is still deaf, so the floor itself is what has to
     * move, and the only argument against moving it was that no handset had
     * ever reported an amplitude.
     *
     * A peak is the wrong number to move it on. A quiet room returned a peak
     * of 427 against a threshold of 500, which reads as no headroom at all --
     * but one transient in three seconds is a door or a chair, not a floor.
     * The mean and the minimum separate a room that is genuinely quiet from
     * one that is not, and two accumulators cost nothing.
     */
    private var voxLevelSum = 0L
    private var voxLevelFloor = Int.MAX_VALUE
    private var voxLevelFrames = 0

    private fun reportVoxLevel(amplitude: Int) {
        if (!voxEnabled) {
            voxLevelPeak = 0
            voxLevelSum = 0
            voxLevelFloor = Int.MAX_VALUE
            voxLevelFrames = 0
            return
        }
        if (amplitude > voxLevelPeak) voxLevelPeak = amplitude
        if (amplitude < voxLevelFloor) voxLevelFloor = amplitude
        voxLevelSum += amplitude
        voxLevelFrames++

        val now = System.currentTimeMillis()
        if (voxLevelReportedAt == 0L) voxLevelReportedAt = now
        if (now - voxLevelReportedAt < VOX_LEVEL_REPORT_MS) return

        val talking = WebSocketManager.isTalkingNow()
        val mean = if (voxLevelFrames > 0) (voxLevelSum / voxLevelFrames).toInt() else 0
        val floor = if (voxLevelFrames > 0) voxLevelFloor else 0

        SafeLog.i(TAG, "vox_level peak=$voxLevelPeak threshold=$voxThreshold " +
            "would_trigger=${voxLevelPeak > voxThreshold} talking=$talking " +
            "blocked_others=${voxBlocks[BLOCK_OTHERS]} " +
            "blocked_playback=${voxBlocks[BLOCK_PLAYBACK]} " +
            "blocked_tone=${voxBlocks[BLOCK_TONE]} " +
            "blocked_interval=${voxBlocks[BLOCK_INTERVAL]} " +
            "mean=$mean floor=$floor frames=$voxLevelFrames")

        /*
         * And to the relay, because logcat is where this number went to die.
         *
         * Since Android 4.1 no app may read another's log, so without a PC and
         * adb the one measurement that decides this fault was locked on the
         * handset that had it. The relay already receives everything else the
         * client says about itself.
         *
         * Reported while transmitting too, deliberately: whether the level
         * stayed above the threshold *during* a transmission is what says
         * whether the silence timer should have been refreshed, which is the
         * whole question.
         */
        WebSocketManager.emit(
            "vox_level",
            JSONObject()
                .put("peak", voxLevelPeak)
                .put("threshold", voxThreshold)
                .put("talking", talking)
                .put("blocked_others", voxBlocks[BLOCK_OTHERS])
                .put("blocked_playback", voxBlocks[BLOCK_PLAYBACK])
                .put("blocked_tone", voxBlocks[BLOCK_TONE])
                .put("blocked_interval", voxBlocks[BLOCK_INTERVAL])
                .put("mean", mean)
                .put("floor", floor),
        )

        voxLevelPeak = 0
        voxLevelSum = 0
        voxLevelFloor = Int.MAX_VALUE
        voxLevelFrames = 0
        voxBlocks.fill(0)
        voxLevelReportedAt = now
    }

    /** Attributes a refusal, but only for a frame that was loud enough to key. */
    private fun noteVoxBlock(which: Int, amplitude: Int) {
        if (amplitude > voxThreshold) voxBlocks[which]++
    }

    private fun handleVoxLogic(amplitude: Int) {
        if (!voxEnabled) return
        val isTalking = WebSocketManager.isTalkingNow()
        if (WebSocketManager.activeSpeakersList.value?.isNotEmpty() == true) {
            if (isTalking) triggerServiceAction(PTTService.ACTION_STOP_PTT)
            else noteVoxBlock(BLOCK_OTHERS, amplitude)
            voxTriggerCount = 0
            return
        }
        /*
         * Whatever the radio is playing is also in this microphone.
         *
         * The guard above covers a remote who is still listed as speaking. It
         * does not cover the two moments that matter most: the playback buffer
         * draining after they leave the list, and the tones. playRxStop() fires
         * exactly as the list empties, and playStopTx() fires just after VOX
         * has closed the operator's own transmission -- when the list is empty
         * by definition. Both go out of the loudspeaker this microphone is
         * listening to, and both are louder than a threshold set for speech.
         *
         * isActuallyPlaying() is the hardware's own account of what it still
         * has to render, and the tone hold-off is the clip's own length.
         */
        if (!isTalking && (AudioPlayer.isActuallyPlaying() || SoundManager.isWithinToneHoldoff())) {
            noteVoxBlock(
                if (AudioPlayer.isActuallyPlaying()) BLOCK_PLAYBACK else BLOCK_TONE,
                amplitude,
            )
            voxTriggerCount = 0
            return
        }
        if (!isTalking) {
            if (amplitude > voxThreshold) {
                voxTriggerCount++
                if (voxTriggerCount >= VOX_TRIGGER_REQUIRED) {
                    val now = System.currentTimeMillis()
                    /*
                     * One trigger per onset. The count used to survive its own
                     * trigger, so every later frame above the threshold sent
                     * another service intent while talking had not started yet
                     * -- and for an RX-only operator, who is always refused, it
                     * never starts: fifty intents a second, each rebuilding the
                     * foreground notification, for as long as they spoke.
                     */
                    voxTriggerCount = 0
                    if (now - lastVoxTriggerAt < VOX_TRIGGER_INTERVAL_MS) {
                        noteVoxBlock(BLOCK_INTERVAL, amplitude)
                        return
                    }
                    lastVoxTriggerAt = now
                    voxSilenceTimer = now
                    triggerServiceAction(PTTService.ACTION_START_PTT, true)
                }
            } else voxTriggerCount = 0
        } else {
            if (amplitude > voxThreshold) voxSilenceTimer = System.currentTimeMillis()
            else if (System.currentTimeMillis() - voxSilenceTimer > VOX_SILENCE_TIMEOUT) {
                triggerServiceAction(PTTService.ACTION_STOP_PTT)
                voxTriggerCount = 0
            }
        }
    }

    /**
     * Ask for the recording back, later and more slowly each time.
     *
     * The wait is served by the service rather than here. This thread is about
     * to exit and the next startRecording joins it; sleeping first would make
     * that join time out and refuse the very restart being asked for.
     */
    private fun requestVoxRestart() {
        if (!voxEnabled && !gatewayModeEnabled) return
        val attempt = voxRestartAttempts
        voxRestartAttempts = attempt + 1
        val delay = (VOX_RESTART_BASE_MS shl attempt.coerceAtMost(VOX_RESTART_MAX_DOUBLINGS))
            .coerceAtMost(VOX_RESTART_MAX_MS)
        SafeLog.e(TAG, "Capture ended unrequested; asking for VOX back in ${delay}ms")
        triggerServiceAction(PTTService.ACTION_VOX_RESTART, restartDelayMs = delay)
    }

    private fun triggerServiceAction(action: String, fromVox: Boolean = false, restartDelayMs: Long = 0L) {
        appContext?.let {
            val intent = Intent(it, PTTService::class.java).apply {
                this.action = action
                if (fromVox) putExtra("from_vox", true)
                if (restartDelayMs > 0L) putExtra(PTTService.EXTRA_RESTART_DELAY_MS, restartDelayMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(it, intent)
                else it.startService(intent)
            } catch (e: Exception) {}
        }
    }

    fun stopRecording(force: Boolean = false) {
        if (force || (!voxEnabled && !gatewayModeEnabled)) {
            isRecording = false
        }
    }

    private fun cleanupAudioRecord() {
        synchronized(this) {
            try {
                audioRecord?.apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        try { stop() } catch (e: Exception) {}
                    }
                    release()
                }
                audioRecord = null
            } catch (e: Exception) {}
        }
    }

    private fun cleanup() {
        cleanupAudioRecord()
        preRoll.clear()
        preTrigger.clear()
        try { opusCodec.destroyEncoder() } catch (e: Exception) {}
    }
}
