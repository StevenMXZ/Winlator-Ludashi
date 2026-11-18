// Reed-Solomon parity for Moonlight video FEC, using nanors (MIT) like Sunshine and
// moonlight-common-c, so the parity matrices match the client's decoder.
#include <jni.h>
#include <stdint.h>
#include <pthread.h>
#include "rs.h"

static pthread_once_t g_rs_once = PTHREAD_ONCE_INIT;
static void rs_init_once(void) { reed_solomon_init(); }

// buf holds (dataShards + parityShards) consecutive shards of blockSize bytes; the parity
// shards are written in place. Returns 0 on success.
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_stream_moonlight_VideoFec_nativeEncode(JNIEnv *env, jclass cls, jbyteArray buf,
                                                              jint dataShards, jint parityShards, jint blockSize) {
    if (dataShards <= 0 || parityShards <= 0 || dataShards + parityShards > 255) return -1;
    pthread_once(&g_rs_once, rs_init_once);
    reed_solomon *rs = reed_solomon_new(dataShards, parityShards);
    if (rs == NULL) return -2;
    uint8_t *shards[255];
    jbyte *b = (*env)->GetPrimitiveArrayCritical(env, buf, NULL);
    if (b == NULL) { reed_solomon_release(rs); return -3; }
    for (int i = 0; i < dataShards + parityShards; i++) shards[i] = (uint8_t *) b + (size_t) i * blockSize;
    int ret = reed_solomon_encode(rs, shards, dataShards + parityShards, blockSize);
    (*env)->ReleasePrimitiveArrayCritical(env, buf, b, 0);
    reed_solomon_release(rs);
    return ret;
}
