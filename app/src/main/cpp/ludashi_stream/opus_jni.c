// JNI bridge to libopus (xiph, BSD-3-Clause) for the Moonlight audio stream.
#include <jni.h>
#include <stdint.h>
#include <opus.h>

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_stream_moonlight_OpusEncoderJni_nativeCreate(JNIEnv *env, jclass cls,
                                                                    jint sampleRate, jint channels, jint bitrate) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_RESTRICTED_LOWDELAY, &err);
    if (err != OPUS_OK || enc == NULL) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_VBR(0));          // constant size packets, like Sunshine
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
    return (jlong) (intptr_t) enc;
}

// Encodes frameSize samples per channel of interleaved s16 PCM. Returns bytes written or < 0.
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_stream_moonlight_OpusEncoderJni_nativeEncode(JNIEnv *env, jclass cls, jlong encPtr,
                                                                    jshortArray pcm, jint frameSize,
                                                                    jbyteArray out) {
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) encPtr;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *dst = (*env)->GetByteArrayElements(env, out, NULL);
    jsize cap = (*env)->GetArrayLength(env, out);
    int n = opus_encode(enc, samples, frameSize, (unsigned char *) dst, cap);
    (*env)->ReleaseByteArrayElements(env, out, dst, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_stream_moonlight_OpusEncoderJni_nativeDestroy(JNIEnv *env, jclass cls, jlong encPtr) {
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) encPtr;
    if (enc) opus_encoder_destroy(enc);
}
