#pragma once
#define JNIEXPORT
#define JNICALL
#define JNI_FALSE 0
#define JNI_TRUE 1
using jint = int;
using jfloat = float;
using jboolean = unsigned char;
using jclass = void*;
using jstring = void*;
struct JNIEnv {
    const char* GetStringUTFChars(jstring s, void*) { return static_cast<const char*>(s); }
    void ReleaseStringUTFChars(jstring, const char*) {}
};
