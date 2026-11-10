#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include "socket_utils.h"

typedef struct XInputStream {
    int fd, capacity, size, position;
    unsigned char* data;
    bool bigEndian;
    int fds[MAX_FDS], fdCount;
} XInputStream;
static XInputStream* XInputStream_allocate(int fd, int capacity) {
    if (capacity <= 0) return NULL;
    XInputStream* s = calloc(1, sizeof(*s)); if (!s) return NULL;
    s->data = malloc(capacity); if (!s->data) { free(s); return NULL; }
    s->fd = fd; s->capacity = capacity; return s;
}
static void XInputStream_destroy(XInputStream* s) {
    if (!s) return;
    for (int i = 0; i < s->fdCount; ++i) close(s->fds[i]);
    free(s->data); free(s);
}
static int XInputStream_readMoreData(XInputStream* s, bool ancillary) {
    if (!s) return -1;
    int remaining = s->size - s->position;
    if (remaining && s->position) memmove(s->data, s->data + s->position, remaining);
    s->size = remaining; s->position = 0;
    if (s->size == s->capacity) {
        if (s->capacity > INT_MAX / 2) return -1;
        int capacity = s->capacity * 2;
        void* data = realloc(s->data, capacity); if (!data) return -1;
        s->data = data; s->capacity = capacity;
    }
    int result;
    if (ancillary) {
        int fds[MAX_FDS], count = 0;
        result = recv_fds(s->fd, fds, &count, s->data + s->size, s->capacity - s->size);
        for (int i = 0; i < count; ++i) {
            if (s->fdCount < MAX_FDS) s->fds[s->fdCount++] = fds[i];
            else { close(fds[i]); result = -1; }
        }
    } else {
        do { result = read(s->fd, s->data + s->size, s->capacity - s->size); } while (result < 0 && errno == EINTR);
    }
    if (result > 0) s->size += result;
    return result;
}
static uint64_t inputValue(XInputStream* s, int bytes) {
    if (!s || bytes > s->size - s->position) return 0;
    uint64_t value = 0;
    for (int i = 0; i < bytes; ++i) value |= (uint64_t)s->data[s->position + i] << (8 * (s->bigEndian ? bytes - 1 - i : i));
    s->position += bytes; return value;
}
JNIEXPORT jlong JNICALL Java_com_winlator_cmod_xconnector_XInputStream_nativeAllocate(JNIEnv* env, jobject obj, jint fd, jint capacity) {
    return (jlong)XInputStream_allocate(fd, capacity);
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_xconnector_XInputStream_readMoreData(JNIEnv* env, jclass type, jlong nativePtr, jboolean ancillary) {
    return XInputStream_readMoreData((XInputStream*)nativePtr, ancillary);
}
JNIEXPORT jobject JNICALL Java_com_winlator_cmod_xconnector_XInputStream_readByteBuffer(JNIEnv* env, jclass type, jlong nativePtr, jint length) {
    XInputStream* s = (XInputStream*)nativePtr;
    if (!s || length < 0 || length > s->size - s->position) return NULL;
    jobject result = (*env)->NewDirectByteBuffer(env, s->data + s->position, length);
    s->position += length; return result;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XInputStream_readBytes(JNIEnv* env, jclass type, jlong nativePtr, jbyteArray output) {
    XInputStream* s = (XInputStream*)nativePtr; jsize length = (*env)->GetArrayLength(env, output);
    if (!s || length > s->size - s->position) return;
    (*env)->SetByteArrayRegion(env, output, 0, length, (jbyte*)(s->data + s->position));
    s->position += length;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XInputStream_destroy(JNIEnv* env, jclass type, jlong nativePtr) {
    XInputStream_destroy((XInputStream*)nativePtr);
}
JNIEXPORT jbyte JNICALL Java_com_winlator_cmod_xconnector_XInputStream_readByte(jlong nativePtr) {
    return (jbyte)inputValue((XInputStream*)nativePtr, 1);
}
JNIEXPORT jshort JNICALL Java_com_winlator_cmod_xconnector_XInputStream_readShort(jlong nativePtr) {
    return (jshort)inputValue((XInputStream*)nativePtr, 2);
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_xconnector_XInputStream_readInt(jlong nativePtr) {
    return (jint)inputValue((XInputStream*)nativePtr, 4);
}
JNIEXPORT jlong JNICALL Java_com_winlator_cmod_xconnector_XInputStream_readLong(jlong nativePtr) {
    return (jlong)inputValue((XInputStream*)nativePtr, 8);
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XInputStream_skip(jlong nativePtr, jint length) {
    XInputStream* s = (XInputStream*)nativePtr; if (s && length >= 0 && length <= s->size - s->position) s->position += length;
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_xconnector_XInputStream_available(jlong nativePtr) {
    XInputStream* s = (XInputStream*)nativePtr; return s ? s->size - s->position : 0;
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_xconnector_XInputStream_getActivePosition(jlong nativePtr) {
    XInputStream* s = (XInputStream*)nativePtr; return s ? s->position : 0;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XInputStream_setActivePosition(jlong nativePtr, jint position) {
    XInputStream* s = (XInputStream*)nativePtr; if (s && position >= 0 && position <= s->size) s->position = position;
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_xconnector_XInputStream_getAncillaryFd(jlong nativePtr) {
    XInputStream* s = (XInputStream*)nativePtr; if (!s || !s->fdCount) return -1;
    int fd = s->fds[0]; memmove(s->fds, s->fds + 1, --s->fdCount * sizeof(int)); return fd;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XInputStream_setByteOrder(jlong nativePtr, jboolean bigEndian) {
    XInputStream* s = (XInputStream*)nativePtr; if (s) s->bigEndian = bigEndian;
}

JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XInputStream_registerNatives(JNIEnv* env, jclass type) {
    const JNINativeMethod methods[] = {
        {"readByte", "(J)B", (void*)Java_com_winlator_cmod_xconnector_XInputStream_readByte},
        {"readShort", "(J)S", (void*)Java_com_winlator_cmod_xconnector_XInputStream_readShort},
        {"readInt", "(J)I", (void*)Java_com_winlator_cmod_xconnector_XInputStream_readInt},
        {"readLong", "(J)J", (void*)Java_com_winlator_cmod_xconnector_XInputStream_readLong},
        {"skip", "(JI)V", (void*)Java_com_winlator_cmod_xconnector_XInputStream_skip},
        {"available", "(J)I", (void*)Java_com_winlator_cmod_xconnector_XInputStream_available},
        {"getActivePosition", "(J)I", (void*)Java_com_winlator_cmod_xconnector_XInputStream_getActivePosition},
        {"setActivePosition", "(JI)V", (void*)Java_com_winlator_cmod_xconnector_XInputStream_setActivePosition},
        {"getAncillaryFd", "(J)I", (void*)Java_com_winlator_cmod_xconnector_XInputStream_getAncillaryFd},
        {"setByteOrder", "(JZ)V", (void*)Java_com_winlator_cmod_xconnector_XInputStream_setByteOrder},
    };
    (*env)->RegisterNatives(env, type, methods, sizeof(methods) / sizeof(methods[0]));
}
