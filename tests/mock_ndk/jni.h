/*
 * mock_ndk/jni.h — Minimal JNI type definitions for host-side testing.
 *
 * Provides just enough of the JNI API surface to compile ahb_bridge.c
 * without the Android NDK.
 */
#pragma once

#include <stdint.h>

/* Primitive JNI types */
typedef uint8_t  jboolean;
typedef int8_t   jbyte;
typedef uint16_t jchar;
typedef int16_t  jshort;
typedef int32_t  jint;
typedef int64_t  jlong;
typedef float    jfloat;
typedef double   jdouble;
typedef void*    jobject;
typedef jobject  jclass;
typedef jobject  jstring;
typedef jobject  jarray;
typedef jarray   jintArray;
typedef void     jvoid;

/* JNIEnv stub — ahb_bridge.c only uses env for (void)env casts */
typedef struct JNINativeInterface_ JNINativeInterface_;
typedef const JNINativeInterface_* JNIEnv;

struct JNINativeInterface_ {
    void* reserved0;
    void* reserved1;
    void* reserved2;
    void* reserved3;
};

/* JNIEXPORT / JNICALL */
#ifdef _MSC_VER
#  define JNIEXPORT __declspec(dllexport)
#  define JNICALL   __cdecl
#else
#  define JNIEXPORT __attribute__((visibility("default")))
#  define JNICALL
#endif
