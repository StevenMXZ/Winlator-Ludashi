#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "lsfg_dll.h"

#define LSFG_FN(name) Java_com_winlator_cmod_lsfg_LosslessScaling_##name

static char* copy_utf(JNIEnv* env, jstring value) {
    if (!value) return NULL;
    const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!chars) return NULL;
    char* copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

JNIEXPORT jint JNICALL LSFG_FN(nativeBuildCache)(JNIEnv* env, jclass clazz, jstring dllPath,
                                                 jstring cachePath, jboolean preferFp16) {
    (void)clazz;
    char* dll = copy_utf(env, dllPath);
    char* cache = copy_utf(env, cachePath);
    LsfgStatus status = LSFG_NOT_INSTALLED;
    if (dll && cache) status = lsfg_build_cache(dll, cache, preferFp16 == JNI_TRUE);
    free(dll);
    free(cache);
    return (jint)status;
}

JNIEXPORT jint JNICALL LSFG_FN(nativeInspectCache)(JNIEnv* env, jclass clazz, jstring cachePath) {
    (void)clazz;
    char* cache = copy_utf(env, cachePath);
    if (!cache) return (jint)LSFG_NOT_INSTALLED;

    LsfgModuleSet set;
    const LsfgStatus status = lsfg_load_modules(cache, &set);
    if (status == LSFG_OK) lsfg_release_modules(&set);
    free(cache);
    return (jint)status;
}

JNIEXPORT jint JNICALL LSFG_FN(nativeCacheVariant)(JNIEnv* env, jclass clazz, jstring cachePath) {
    (void)clazz;
    char* cache = copy_utf(env, cachePath);
    if (!cache) return (jint)LSFG_VARIANT_NONE;

    LsfgModuleSet set;
    LsfgVariant variant = LSFG_VARIANT_NONE;
    if (lsfg_load_modules(cache, &set) == LSFG_OK) {
        variant = set.variant;
        lsfg_release_modules(&set);
    }
    free(cache);
    return (jint)variant;
}
