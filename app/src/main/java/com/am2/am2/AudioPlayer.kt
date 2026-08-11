package com.am2.am2

import com.am2.am2.logging.SafeLog

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

object AudioPlayer {
    private const val TAG = "AudioPlayer"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MIN_BUFFER_SIZE = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playbackThread: Thread? = null

    private var useVoiceCommunication = false

    private val remoteDecoders = ConcurrentHashMap<String, RemoteUserAudioHandler>()
    private var lastDataTime = 0L
    private const val SILENCE_TIMEOUT_MS = 1500L
    private val silentBuffer = ShortArray(320)
    
    private var userVolume: Float = 1.0f
    private var isMuted: Boolean = false
    private var totalFramesWritten = 0L
    private var lastDataWriteTime = 0L

    fun isActuallyPlaying(): Boolean {
        val track = audioTrack ?: return false
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return false
        
        val head = try { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (e: Exception) { 0L }
        
        // Cek apakah hardware masih memproses data
        val isHardwarePlaying = totalFramesWritten > head
        
        // Hang-time (Grace Period) 1000ms untuk menutupi latency hardware/OS 
        // agar UI tidak berkedip ke IDLE saat suara sebenarnya masih terdengar tipis di akhir.
        val isWithinGracePeriod = (System.currentTimeMillis() - lastDataWriteTime) < 1000
        
        return isHardwarePlaying || isWithinGracePeriod
    }

    fun setVolume(volume: Float) {
        this.userVolume = volume.coerceIn(0f, 1.0f)
        applyVolume()
    }

    fun setMute(mute: Boolean) {
        this.isMuted = mute
        applyVolume()
    }

    private fun applyVolume() {
        val vol = if (isMuted) 0f else userVolume
        try {
            audioTrack?.let { track ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track.setVolume(vol)
                } else {
                    @Suppress("DEPRECATION")
                    track.setStereoVolume(vol, vol)
                }
            }
        } catch (e: Exception) {}
    }

    private class AudioFilter(sampleRate: Int) {
        private var lowState = 0f
        private var midState = 0f
        
        private val alphaLow = (2.0 * Math.PI * 450.0 / (sampleRate + 2.0 * Math.PI * 450.0)).toFloat()
        private val alphaMid = (2.0 * Math.PI * 2200.0 / (sampleRate + 2.0 * Math.PI * 2200.0)).toFloat()

        fun apply(buffer: ShortArray) {
            for (i in buffer.indices) {
                val sample = buffer[i].toFloat()
                
                lowState += alphaLow * (sample - lowState)
                val bass = lowState
                
                midState += alphaMid * (sample - midState)
                val mid = midState - bass
                
                val treble = sample - midState
                
                val processed = (bass * 1.0f) + (mid * 1.0f) + (treble * 0.8f)
                
                buffer[i] = processed.coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }
    }

    class RemoteUserAudioHandler(val sender: String) {
        val opusCodec = OpusCodec()
        val queue = LinkedBlockingQueue<ByteArray>(200)
        var lastSeen = System.currentTimeMillis()
        private var isBuffering = true
        
        private val JITTER_THRESHOLD = 8 

        init { opusCodec.createDecoder(SAMPLE_RATE) }

        fun decodeNext(): ShortArray? {
            if (isBuffering) {
                if (queue.size >= JITTER_THRESHOLD) isBuffering = false
                else return null
            }
            val data = queue.poll()
            if (data == null) {
                isBuffering = true
                return null
            }
            return try { opusCodec.decode(data) } catch (e: Exception) { null }
        }

        fun release() { try { opusCodec.destroyDecoder() } catch (e: Exception) {} }
    }

    fun updateConfig(usage: Int, keepAlive: Boolean) {
        val useVoiceComm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            usage == AudioManager.STREAM_VOICE_CALL
        }
        updateAudioRouting(useVoiceComm)
    }

    fun updateAudioRouting(useVoiceComm: Boolean) {
        if (this.useVoiceCommunication != useVoiceComm) {
            this.useVoiceCommunication = useVoiceComm
            setupAudioTrack("Routing Change")
        }
    }

    @SuppressLint("NewApi")
    private fun setupAudioTrack(reason: String) {
        synchronized(this) {
            try {
                audioTrack?.apply {
                    try {
                        if (state == AudioTrack.STATE_INITIALIZED) {
                            stop()
                            flush()
                        }
                        release()
                    } catch (e: Exception) {}
                }
                audioTrack = null
                totalFramesWritten = 0L
                lastDataWriteTime = 0L

                val bufferSize = (MIN_BUFFER_SIZE * 8).coerceAtLeast(16384)

                audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val usage = if (useVoiceCommunication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
                    val contentType = if (useVoiceCommunication) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC

                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    val streamType = if (useVoiceCommunication) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
                    AudioTrack(streamType, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize, AudioTrack.MODE_STREAM)
                }
                
                applyVolume()
                if (playbackThread == null) startMixerThread()
            } catch (e: Exception) {
                SafeLog.e(TAG, "AudioTrack Setup Error: ${e.message}")
            }
        }
    }

    private fun startMixerThread() {
        if (isPlaying) return
        isPlaying = true

        playbackThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            val audioFilter = AudioFilter(SAMPLE_RATE)
            while (isPlaying) {
                try {
                    val activeFrames = mutableListOf<ShortArray>()
                    val now = System.currentTimeMillis()

                    val iterator = remoteDecoders.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val handler = entry.value
                        if (now - handler.lastSeen > 5000) {
                            handler.release()
                            iterator.remove()
                            continue
                        }
                        val pcm = handler.decodeNext()
                        if (pcm != null) activeFrames.add(pcm)
                    }

                    if (activeFrames.isNotEmpty()) {
                        lastDataTime = now
                        val frameSize = activeFrames[0].size
                        val mixedFrame = ShortArray(frameSize)
                        for (i in 0 until frameSize) {
                            var sum = 0
                            for (frame in activeFrames) if (i < frame.size) sum += frame[i]
                            mixedFrame[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }

                        audioFilter.apply(mixedFrame)

                        synchronized(this) {
                            val track = audioTrack
                            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                                val written = track.write(mixedFrame, 0, mixedFrame.size)
                                if (written > 0) {
                                    totalFramesWritten += written
                                    lastDataWriteTime = System.currentTimeMillis()
                                }
                            }
                        }
                    } else {
                        if (now - lastDataTime < SILENCE_TIMEOUT_MS && lastDataTime > 0) {
                            synchronized(this) {
                                val track = audioTrack
                                if (track != null && track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                    track.write(silentBuffer, 0, silentBuffer.size)
                                }
                            }
                            Thread.sleep(10)
                        } else {
                            synchronized(this) {
                                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                    audioTrack?.pause()
                                }
                            }
                            Thread.sleep(40)
                        }
                    }
                } catch (e: Exception) {
                    try { Thread.sleep(20) } catch (i: InterruptedException) { break }
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            name = "AudioMixerThread"
            start()
        }
    }

    fun playAudio(sender: String, data: ByteArray) {
        val handler = remoteDecoders.getOrPut(sender) { RemoteUserAudioHandler(sender) }
        handler.lastSeen = System.currentTimeMillis()
        if (handler.queue.size > 150) handler.queue.poll()
        handler.queue.offer(data)
        
        synchronized(this) {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                setupAudioTrack("Audio Received")
            }
        }
    }

    fun stop() {
        remoteDecoders.values.forEach { it.queue.clear() }
        synchronized(this) {
            try {
                audioTrack?.apply {
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        pause()
                        flush()
                    }
                }
                totalFramesWritten = 0L
                lastDataWriteTime = 0L
            } catch (e: Exception) {}
        }
    }

    fun release() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread = null
        remoteDecoders.values.forEach { it.release() }
        remoteDecoders.clear()
        synchronized(this) {
            try {
                audioTrack?.apply {
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                    }
                    release()
                }
            } catch (e: Exception) {}
            audioTrack = null
        }
    }
}
