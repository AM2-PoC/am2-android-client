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
    private var currentAudioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    @Volatile
    var voxEnabled = false
    @Volatile
    var gatewayModeEnabled = false

    private var voxThreshold = 2200 
    private var voxSilenceTimer = 0L
    private const val VOX_SILENCE_TIMEOUT = 1500L 
    private var voxTriggerCount = 0
    private const val VOX_TRIGGER_REQUIRED = 1 
    
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
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

    fun updateConfig(source: Int) {
        val useVoiceComm = (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        updateAudioSource(useVoiceComm)
    }

    fun updateAudioSource(useVoiceComm: Boolean) {
        currentAudioSource = if (useVoiceComm) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
    }

    @SuppressLint("MissingPermission")
    fun startRecording(channelSlug: String) {
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

            recordingThread = thread(priority = Thread.MAX_PRIORITY, name = "AudioRecordThread") {
                try {
                    var success = false
                    val sources = arrayOf(currentAudioSource, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
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

                    if (success && isRecording) {
                        val pcmBuffer = ShortArray(FRAME_SIZE)
                        val audioFilter = AudioFilter(SAMPLE_RATE)
                        while (isRecording) {
                            val recorder = audioRecord ?: break
                            val read = try {
                                recorder.read(pcmBuffer, 0, FRAME_SIZE)
                            } catch (e: Exception) { -1 }

                            if (read == FRAME_SIZE) {
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
                                        // Hand every frame over. Whether it can
                                        // go is sendBinary's decision, and it is
                                        // the only place that can record it.
                                        WebSocketManager.sendBinary(packet)
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
                    isRecording = false
                    cleanup()
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

    private fun handleVoxLogic(amplitude: Int) {
        if (!voxEnabled) return
        val isTalking = WebSocketManager.isTalkingNow()
        if (WebSocketManager.activeSpeakersList.value?.isNotEmpty() == true) {
            if (isTalking) triggerServiceAction(PTTService.ACTION_STOP_PTT)
            voxTriggerCount = 0
            return
        }
        if (!isTalking) {
            if (amplitude > voxThreshold) {
                voxTriggerCount++
                if (voxTriggerCount >= VOX_TRIGGER_REQUIRED) {
                    voxSilenceTimer = System.currentTimeMillis()
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

    private fun triggerServiceAction(action: String, fromVox: Boolean = false) {
        appContext?.let {
            val intent = Intent(it, PTTService::class.java).apply { 
                this.action = action 
                if (fromVox) putExtra("from_vox", true)
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
        try { opusCodec.destroyEncoder() } catch (e: Exception) {}
    }
}
