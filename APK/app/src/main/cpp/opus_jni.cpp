#include <jni.h>
#include <opus.h>
#include <android/log.h>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OPUS_JNI", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_am2_am2_OpusCodec_nativeCreateEncoder(JNIEnv *env, jobject thiz, jint sampleRate, jint bitrate, jint complexity) {
    int err;
    OpusEncoder *enc = opus_encoder_create(sampleRate, 1, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK) {
        LOGE("Failed to create encoder: %d", err);
        return 0;
    }

    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    opus_encoder_ctl(enc, OPUS_SET_PREDICTION_DISABLED(0));

    return (jlong)enc;
}

JNIEXPORT jlong JNICALL
Java_com_am2_am2_OpusCodec_nativeCreateDecoder(JNIEnv *env, jobject thiz, jint sampleRate) {
    int err;
    OpusDecoder *dec = opus_decoder_create(sampleRate, 1, &err);
    if (err != OPUS_OK) {
        LOGE("Failed to create decoder: %d", err);
        return 0;
    }
    return (jlong)dec;
}

JNIEXPORT jbyteArray JNICALL
Java_com_am2_am2_OpusCodec_nativeEncode(JNIEnv *env, jobject thiz, jlong handle, jshortArray pcm, jint frameSize) {
    OpusEncoder *enc = (OpusEncoder *)handle;
    if (!enc || !pcm) return nullptr;

    jshort *pcmData = env->GetShortArrayElements(pcm, nullptr);
    if (!pcmData) return nullptr;

    // Buffer maksimal sesuai spesifikasi Opus
    unsigned char outBuf[1275];

    int len = opus_encode(enc, pcmData, frameSize, outBuf, sizeof(outBuf));

    env->ReleaseShortArrayElements(pcm, pcmData, JNI_ABORT);

    if (len <= 0) {
        if (len < 0) LOGE("Encode error: %d", len);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(len);
    if (result) {
        env->SetByteArrayRegion(result, 0, len, (jbyte *)outBuf);
    }
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_am2_am2_OpusCodec_nativeDecode(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data) {
    OpusDecoder *dec = (OpusDecoder *)handle;
    if (!dec) return nullptr;

    // Gunakan buffer yang cukup besar tapi aman
    // 5760 adalah max frame size Opus (120ms @ 48kHz)
    const int MAX_FRAME_SIZE = 5760;
    short pcmOut[MAX_FRAME_SIZE];

    int samples = 0;
    if (data == nullptr) {
        // PLC (Packet Loss Concealment)
        // Gunakan frame size standar (misal 20ms @ 16kHz = 320)
        samples = opus_decode(dec, nullptr, 0, pcmOut, 320, 0);
    } else {
        jsize len = env->GetArrayLength(data);
        jbyte *opusData = env->GetByteArrayElements(data, nullptr);
        if (opusData) {
            samples = opus_decode(dec, (unsigned char *)opusData, len, pcmOut, MAX_FRAME_SIZE, 0);
            env->ReleaseByteArrayElements(data, opusData, JNI_ABORT);
        }
    }

    if (samples <= 0) {
        if (samples < 0 && data != nullptr) LOGE("Decode error: %d", samples);
        return nullptr;
    }

    jshortArray out = env->NewShortArray(samples);
    if (out) {
        env->SetShortArrayRegion(out, 0, samples, pcmOut);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_com_am2_am2_OpusCodec_nativeDestroyEncoder(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle) {
        opus_encoder_destroy((OpusEncoder *)handle);
    }
}

JNIEXPORT void JNICALL
Java_com_am2_am2_OpusCodec_nativeDestroyDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle) {
        opus_decoder_destroy((OpusDecoder *)handle);
    }
}

}
