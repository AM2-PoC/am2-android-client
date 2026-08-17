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

    /** Sensitivity when the operator has never chosen one. */
    const val VOX_THRESHOLD_DEFAULT = 2200
    /* The settings screen maps its slider onto this range. */
    const val VOX_THRESHOLD_MIN = 500
    const val VOX_THRESHOLD_MAX = 12000

    @Volatile
    private var audioRecord: AudioRecord? = null

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
    private var voxThreshold = VOX_THRESHOLD_DEFAULT
    private var voxSilenceTimer = 0L
    private const val VOX_SILENCE_TIMEOUT = 1500L
    private var voxTriggerCount = 0
    private const val VOX_TRIGGER_REQUIRED = 1
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
        voxThreshold = threshold.coerceIn(VOX_THRESHOLD_MIN, VOX_THRESHOLD_MAX)
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

                                // Gunakan isTalkingNow() untuk respons yang lebih cepat (tanpa delay LiveData)
                                val talking = WebSocketManager.isTalkingNow()
                                if (talking) {
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
                                } else if (preRoll.isNotEmpty()) {
                                    // The transmission ended without ever being
                                    // authorized. Nothing left to flush to.
                                    preRoll.clear()
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

    private fun flushPreRoll() {
        while (preRoll.isNotEmpty()) {
            WebSocketManager.sendBinary(preRoll.removeFirst())
        }
    }

    private fun handleVoxLogic(amplitude: Int) {
        if (!voxEnabled) return
        val isTalking = WebSocketManager.isTalkingNow()
        if (WebSocketManager.activeSpeakersList.value?.isNotEmpty() == true) {
            if (isTalking) triggerServiceAction(PTTService.ACTION_STOP_PTT)
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
                    if (now - lastVoxTriggerAt < VOX_TRIGGER_INTERVAL_MS) return
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
        try { opusCodec.destroyEncoder() } catch (e: Exception) {}
    }
}
