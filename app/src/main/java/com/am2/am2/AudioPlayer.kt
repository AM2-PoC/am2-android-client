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
    private const val FRAME_BYTES = 640
    /* Long enough for the mixer to leave its loop at a frame boundary. */
    private const val MIXER_DRAIN_TIMEOUT_MS = 200L
    /** Write-to-head-position reporting lag, not the length of a transmission. */
    private const val PLAYBACK_REPORT_GRACE_MS = 150L
    /* One frame, so a wait costs exactly what a frame is worth. */
    private const val FRAME_MS = 20L
    private val setupPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val setupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    private var playbackThread: Thread? = null

    private var useVoiceCommunication = false

    private val remoteDecoders = ConcurrentHashMap<String, RemoteUserAudioHandler>()
    private var lastDataTime = 0L
    private const val SILENCE_TIMEOUT_MS = 1500L
    private val silentBuffer = ShortArray(320)

    /*
     * One frame is 20 ms, so every frame held anywhere on this path is 20 ms the
     * listener waits. Prefill starts at the smallest amount that survives normal
     * mobile jitter and only grows while a network keeps underrunning, then
     * decays back so the delay is given up again once conditions improve.
     */
    private const val MIN_PREFILL_FRAMES = 3
    private const val MAX_PREFILL_FRAMES = 10
    /* A gap this long is a talk spurt that ended, not jitter. Only then is it
     * right to prefill again; shorter gaps are covered by silence. */
    private const val END_OF_SPURT_FRAMES = 15
    /* Backlog above this is delay that will never be heard as anything but
     * lateness, so the oldest frames are shed to recover it. */
    private const val HIGH_WATER_FRAMES = 15
    private const val PREFILL_DECAY_FRAMES = 250
    
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
        
        /*
         * Only the reporting lag, not the transmission.
         *
         * The line above is the precise answer: the hardware's own account of
         * what it still has to render. This covers the short window where a
         * write has landed but the head position has not caught up yet, so the
         * indicator does not flicker to idle mid-speech.
         *
         * It was a flat second, which is not lag -- it is long enough to
         * outlast the tail entirely, so the receiving handset kept showing the
         * sender as talking well after the audio had finished and the relay had
         * already broadcast an empty speaker list.
         */
        val isWithinGracePeriod =
            (System.currentTimeMillis() - lastDataWriteTime) < PLAYBACK_REPORT_GRACE_MS
        
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

    private data class ReceivedFrame(val data: ByteArray, val sequence: Long)
    private data class DecodedFrame(val pcm: ShortArray, val traceId: Long, val sequence: Long)

    private class RemoteUserAudioHandler(val sender: String, val traceId: Long) {
        val opusCodec = OpusCodec()
        val queue = LinkedBlockingQueue<ReceivedFrame>(200)
        @Volatile
        var lastSeen = System.currentTimeMillis()
        private var nextSequence = 0L
        private var isBuffering = true

        private var targetPrefill = MIN_PREFILL_FRAMES
        private var consecutiveUnderruns = 0
        private var framesSinceUnderrun = 0L

        init { opusCodec.createDecoder(SAMPLE_RATE) }

        /*
         * Mutually exclusive with release(), which the mixer calls when a
         * sender goes quiet. Without that, the reader thread could land a frame
         * in a queue being cleared, on a handler whose decoder was being
         * destroyed — lost with nothing to show for it.
         */
        @Synchronized
        fun enqueue(data: ByteArray) {
            val sequence = ++nextSequence
            // Shed the oldest frames rather than the newest: the stale ones are
            // what the listener would hear as lateness.
            while (queue.size > HIGH_WATER_FRAMES) queue.poll()
            queue.offer(ReceivedFrame(data, sequence))
            if (PttTrace.shouldSampleFrame(sequence)) {
                PttTrace.emit(
                    event = "frame_received",
                    traceId = traceId,
                    frameSequence = sequence,
                    frameBytes = data.size,
                    queueFrames = queue.size,
                )
            }
        }

        @Synchronized
        fun decodeNext(): DecodedFrame? {
            if (isBuffering) {
                if (queue.size >= targetPrefill) {
                    isBuffering = false
                    consecutiveUnderruns = 0
                } else return null
            }

            val frame = queue.poll()
            if (frame == null) {
                // A gap is normal. The mixer covers it with silence and the
                // stream resumes on the next frame; only a gap long enough to be
                // the end of the spurt earns a fresh prefill.
                consecutiveUnderruns++
                framesSinceUnderrun = 0
                if (consecutiveUnderruns == 1 && targetPrefill < MAX_PREFILL_FRAMES) {
                    targetPrefill++
                }
                if (consecutiveUnderruns >= END_OF_SPURT_FRAMES) isBuffering = true
                return null
            }

            consecutiveUnderruns = 0
            // Give the added prefill back once the network has behaved for a while.
            if (++framesSinceUnderrun >= PREFILL_DECAY_FRAMES) {
                framesSinceUnderrun = 0
                if (targetPrefill > MIN_PREFILL_FRAMES) targetPrefill--
            }

            return try {
                opusCodec.decode(frame.data)?.let { pcm ->
                    if (PttTrace.shouldSampleFrame(frame.sequence)) {
                        PttTrace.emit(
                            event = "frame_decoded",
                            traceId = traceId,
                            frameSequence = frame.sequence,
                            frameBytes = frame.data.size,
                            queueFrames = queue.size,
                        )
                    }
                    DecodedFrame(pcm, traceId, frame.sequence)
                }
            } catch (e: Exception) {
                null
            }
        }

        @Synchronized
        fun release() {
            queue.clear()
            try { opusCodec.destroyDecoder() } catch (e: Exception) {}
        }
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

                /*
                 * Sized to pace playback, not to hide jitter. A large track
                 * buffer is a second jitter buffer downstream of the queue:
                 * the mixer fills it as fast as write() permits, and backlog
                 * parked there is invisible to the queue and can never be
                 * recovered. Kept at the device minimum with a small floor so
                 * a blocking write paces the mixer at real time instead.
                 */
                val bufferSize = MIN_BUFFER_SIZE.coerceAtLeast(FRAME_BYTES * 4)

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
                    val activeFrames = ArrayList<DecodedFrame>()
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
                        val frameSize = activeFrames[0].pcm.size
                        val mixedFrame = ShortArray(frameSize)
                        for (i in 0 until frameSize) {
                            var sum = 0
                            for (frame in activeFrames) if (i < frame.pcm.size) sum += frame.pcm[i]
                            mixedFrame[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }

                        audioFilter.apply(mixedFrame)

                        // write() blocks once the track buffer is full, and that
                        // is what paces this loop. playAudio() takes this lock on
                        // the network thread, so the write must happen outside it
                        // or the network thread blocks on playback.
                        val track = synchronized(this) { audioTrack }
                        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                            /*
                             * No track to write to yet. Without this the loop
                             * re-enters at once, decodes every handler again and
                             * throws the PCM away — draining at CPU speed the
                             * jitter buffer it had just filled, and burning a
                             * core while doing it.
                             */
                            requestAudioTrack()
                            Thread.sleep(FRAME_MS)
                        } else {
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                            val written = track.write(mixedFrame, 0, mixedFrame.size)
                            if (written > 0) {
                                totalFramesWritten += written
                                lastDataWriteTime = System.currentTimeMillis()
                                activeFrames.forEach { frame ->
                                    if (PttTrace.shouldSampleFrame(frame.sequence)) {
                                        PttTrace.emit(
                                            event = "playback_written",
                                            traceId = frame.traceId,
                                            frameSequence = frame.sequence,
                                            frameBytes = written * 2,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        if (now - lastDataTime < SILENCE_TIMEOUT_MS && lastDataTime > 0) {
                            // Cover the gap and keep the playback clock running,
                            // so a resuming spurt does not have to prefill again.
                            val track = synchronized(this) { audioTrack }
                            val written = if (track != null && track.state == AudioTrack.STATE_INITIALIZED &&
                                track.playState == AudioTrack.PLAYSTATE_PLAYING
                            ) {
                                track.write(silentBuffer, 0, silentBuffer.size)
                            } else 0
                            // The blocking write is the pacer; only sleep when it
                            // did not run, so silence is never produced faster
                            // than it is consumed.
                            if (written <= 0) Thread.sleep(20)
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

    fun playAudio(sender: String, data: ByteArray, traceId: Long) {
        val handler = remoteDecoders.compute(sender) { _, current ->
            if (current == null || current.traceId != traceId) {
                current?.release()
                RemoteUserAudioHandler(sender, traceId)
            } else current
        } ?: return
        handler.lastSeen = System.currentTimeMillis()
        handler.enqueue(data)
        
        requestAudioTrack()
    }

    /*
     * Ask for a track; do not build one here.
     *
     * playAudio runs on the OkHttp reader thread. Building an AudioTrack takes
     * 10-50 ms and used to happen inline under this object's monitor, during
     * which nothing was read from the socket at all — not this stream's audio,
     * not anyone else's, and not video. The stall showed up as everything
     * pausing together, which reads like the network rather than like us.
     *
     * The mixer tolerates a missing track by waiting, so handing the work to a
     * dedicated thread costs nothing and keeps the reader free.
     */
    private fun requestAudioTrack() {
        val track = synchronized(this) { audioTrack }
        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) return
        if (!setupPending.compareAndSet(false, true)) return
        setupExecutor.execute {
            try {
                setupAudioTrack("Audio Received")
            } finally {
                setupPending.set(false)
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
        playbackThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(MIXER_DRAIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
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
