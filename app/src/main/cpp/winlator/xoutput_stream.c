#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include "socket_utils.h"

typedef struct XOutputStream {
    int fd, ancillaryFd, capacity, position;
    unsigned char* data;
    bool bigEndian, failed;
} XOutputStream;
static bool ensureSpaceIsAvailable(XOutputStream* s, int length) {
    if (!s || s->failed) return false;
    if (length < 0 || length > INT_MAX - s->position) { s->failed = true; return false; }
    int required = s->position + length;
    if (required <= s->capacity) return true;
    int capacity = s->capacity <= INT_MAX / 2 ? s->capacity * 2 : INT_MAX;
    if (capacity < required) capacity = required;
    void* data = realloc(s->data, capacity);
    if (!data) { s->failed = true; return false; }
    s->data = data; s->capacity = capacity; return true;
}
static XOutputStream* XOutputStream_allocate(int fd, int capacity) {
    if (capacity <= 0) return NULL;
    XOutputStream* s = calloc(1, sizeof(*s)); if (!s) return NULL;
    s->data = malloc(capacity); if (!s->data) { free(s); return NULL; }
    s->capacity = capacity; s->fd = fd; s->ancillaryFd = -1; return s;
}
static void XOutputStream_destroy(XOutputStream* s) { if (s) { free(s->data); free(s); } }
static jboolean XOutputStream_send(XOutputStream* s) {
    if (!s || s->failed) return JNI_FALSE;
    int offset = 0;
    while (offset < s->position) {
        int sent;
        if (s->ancillaryFd >= 0) {
            sent = send_fds(s->fd, &s->ancillaryFd, 1, s->data + offset, s->position - offset);
            if (sent > 0) s->ancillaryFd = -1;
        } else {
            do { sent = send(s->fd, s->data + offset, s->position - offset, MSG_NOSIGNAL); } while (sent < 0 && errno == EINTR);
        }
        if (sent <= 0) { s->failed = true; return JNI_FALSE; }
        offset += sent;
    }
    s->position = 0; return JNI_TRUE;
}
static void outputValue(XOutputStream* s, uint64_t value, int bytes) {
    if (!ensureSpaceIsAvailable(s, bytes)) return;
    for (int i = 0; i < bytes; ++i) s->data[s->position++] = value >> (8 * (s->bigEndian ? bytes - 1 - i : i));
}
JNIEXPORT jlong JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_nativeAllocate(JNIEnv* env, jobject obj, jint fd, jint capacity) { return (jlong)XOutputStream_allocate(fd, capacity); }
JNIEXPORT jboolean JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_sendData(JNIEnv* env, jclass type, jlong nativePtr) { return XOutputStream_send((XOutputStream*)nativePtr); }
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_destroy(JNIEnv* env, jclass type, jlong nativePtr) { XOutputStream_destroy((XOutputStream*)nativePtr); }
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_writeBytes(JNIEnv* env, jclass type, jlong nativePtr, jbyteArray data, jint offset, jint length) {
    XOutputStream* s = (XOutputStream*)nativePtr;
    if (!ensureSpaceIsAvailable(s, length)) return;
    (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte*)(s->data + s->position)); s->position += length;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_writeAt(JNIEnv* env, jclass type, jlong nativePtr, jint position, jbyteArray data) {
    XOutputStream* s = (XOutputStream*)nativePtr; jsize length = (*env)->GetArrayLength(env, data);
    if (!s || position < 0 || position > s->position || length > s->position - position) return;
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte*)(s->data + position));
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_writeByteBuffer(JNIEnv* env, jclass type, jlong nativePtr, jobject data, jint offset, jint length) {
    XOutputStream* s = (XOutputStream*)nativePtr; void* address = (*env)->GetDirectBufferAddress(env, data);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    if (!address || offset < 0 || length < 0 || offset > capacity || length > capacity - offset || !ensureSpaceIsAvailable(s, length)) return;
    memcpy(s->data + s->position, (char*)address + offset, length); s->position += length;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_writeByte(jlong nativePtr, jbyte value) {
    outputValue((XOutputStream*)nativePtr, (uint64_t)value, 1);
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_writeShort(jlong nativePtr, jshort value) {
    outputValue((XOutputStream*)nativePtr, (uint64_t)value, 2);
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_writeInt(jlong nativePtr, jint value) {
    outputValue((XOutputStream*)nativePtr, (uint64_t)value, 4);
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_writeLong(jlong nativePtr, jlong value) {
    outputValue((XOutputStream*)nativePtr, (uint64_t)value, 8);
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_setAncillaryFd(jlong nativePtr, jint fd) {
    XOutputStream* s = (XOutputStream*)nativePtr; if (s) s->ancillaryFd = fd;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_setByteOrder(jlong nativePtr, jboolean bigEndian) {
    XOutputStream* s = (XOutputStream*)nativePtr; if (s) s->bigEndian = bigEndian;
}
JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_writePad(jlong nativePtr, jint length) {
    XOutputStream* s = (XOutputStream*)nativePtr; if (ensureSpaceIsAvailable(s, length)) { memset(s->data + s->position, 0, length); s->position += length; }
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_length(jlong nativePtr) {
    XOutputStream* s = (XOutputStream*)nativePtr; return s ? s->position : 0;
}

JNIEXPORT void JNICALL Java_com_winlator_cmod_xconnector_XOutputStream_registerNatives(JNIEnv* env, jclass type) {
    const JNINativeMethod methods[] = {
        {"writeByte", "(JB)V", (void*)Java_com_winlator_cmod_xconnector_XOutputStream_writeByte},
        {"writeShort", "(JS)V", (void*)Java_com_winlator_cmod_xconnector_XOutputStream_writeShort},
        {"writeInt", "(JI)V", (void*)Java_com_winlator_cmod_xconnector_XOutputStream_writeInt},
        {"writeLong", "(JJ)V", (void*)Java_com_winlator_cmod_xconnector_XOutputStream_writeLong},
        {"setAncillaryFd", "(JI)V", (void*)Java_com_winlator_cmod_xconnector_XOutputStream_setAncillaryFd},
        {"setByteOrder", "(JZ)V", (void*)Java_com_winlator_cmod_xconnector_XOutputStream_setByteOrder},
        {"writePad", "(JI)V", (void*)Java_com_winlator_cmod_xconnector_XOutputStream_writePad},
        {"length", "(J)I", (void*)Java_com_winlator_cmod_xconnector_XOutputStream_length},
    };
    (*env)->RegisterNatives(env, type, methods, sizeof(methods) / sizeof(methods[0]));
}
