package com.am2.am2

import android.util.Log
import androidx.annotation.Keep

class OpusCodec {

    @Keep
    private external fun nativeCreateEncoder(sampleRate: Int, bitrate: Int, complexity: Int): Long

    @Keep
    private external fun nativeCreateDecoder(sampleRate: Int): Long

    @Keep
    private external fun nativeEncode(handle: Long, pcm: ShortArray, frameSize: Int): ByteArray?

    @Keep
    private external fun nativeDecode(handle: Long, opus: ByteArray): ShortArray?

    @Keep
    private external fun nativeDestroyEncoder(handle: Long)

    @Keep
    private external fun nativeDestroyDecoder(handle: Long)

    private var encoderHandle: Long = 0
    private var decoderHandle: Long = 0

    companion object {
        private const val TAG = "OpusCodec"
        private var isLibraryLoaded = false

        init {
            try {
                System.loadLibrary("opus_jni")
                isLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load opus_jni", e)
            }
        }
    }

    fun createEncoder(sampleRate: Int = 16000, bitrate: Int = 24000, complexity: Int = 5) {
        if (!isLibraryLoaded) return
        if (encoderHandle != 0L) destroyEncoder()
        encoderHandle = nativeCreateEncoder(sampleRate, bitrate, complexity)
    }

    fun createDecoder(sampleRate: Int = 16000) {
        if (!isLibraryLoaded) return
        if (decoderHandle != 0L) destroyDecoder()
        decoderHandle = nativeCreateDecoder(sampleRate)
    }

    fun encode(pcm: ShortArray, frameSize: Int): ByteArray? {
        if (encoderHandle == 0L) return null
        return nativeEncode(encoderHandle, pcm, frameSize)
    }

    fun decode(opus: ByteArray): ShortArray? {
        if (decoderHandle == 0L) return null
        return nativeDecode(decoderHandle, opus)
    }

    fun destroyEncoder() {
        if (encoderHandle != 0L) {
            nativeDestroyEncoder(encoderHandle)
            encoderHandle = 0
        }
    }

    fun destroyDecoder() {
        if (decoderHandle != 0L) {
            nativeDestroyDecoder(decoderHandle)
            decoderHandle = 0
        }
    }
}
