#include <malloc.h>
#include <string.h>
#include <jni.h>
#include <unistd.h>

#include "socket_utils.h"
#include "winlator.h"
#include "arrays.h"

typedef struct XInputBuffer {
    void* data;
    int capacity;
    int position;
    int limit;
} XInputBuffer;

typedef struct XInputStream {
    int fd;
    XInputBuffer buffer;
    XInputBuffer activeBuffer;
    IntArray ancillaryFds;
} XInputStream;

static int ancillaryFds[MAX_FDS] = {0};

static void growInputBufferIfNecessary(XInputBuffer* buffer) {
    if (buffer->position == buffer->capacity) {
        int newCapacity = buffer->capacity * 2;
        void* newData = realloc(buffer->data, newCapacity);
        buffer->capacity = newCapacity;
        buffer->limit = newCapacity;
        buffer->data = newData;
    }
}

static void compactInputBuffer(XInputBuffer* buffer) {
    int remaining = buffer->limit - buffer->position;
    memcpy(buffer->data + 0, buffer->data + buffer->position, remaining);
    buffer->position = remaining;
    buffer->limit = buffer->capacity;
}

static void activateInputBuffer(XInputBuffer* buffer, XInputBuffer* activeBuffer) {
    int limit = buffer->position;
    activeBuffer->data = buffer->data;
    activeBuffer->position = 0;
    activeBuffer->limit = limit;
    activeBuffer->capacity = limit;
}

static XInputStream* XInputStream_allocate(int fd, int initialCapacity) {
    XInputStream* inputStream = calloc(1, sizeof(XInputStream));
    inputStream->fd = fd;
    inputStream->buffer.data = calloc(initialCapacity, 1);
    inputStream->buffer.capacity = initialCapacity;
    inputStream->buffer.limit = initialCapacity;
    return inputStream;
}

static void XInputStream_destroy(XInputStream* inputStream) {
    if (!inputStream) return;
    IntArray_clear(&inputStream->ancillaryFds);
    MEMFREE(inputStream->buffer.data);
    MEMFREE(inputStream);
}

static int XInputStream_read(XInputStream* inputStream) {
    int remaining = inputStream->buffer.limit - inputStream->buffer.position;
    int bytesRead = read(inputStream->fd, inputStream->buffer.data + inputStream->buffer.position, remaining);
    if (bytesRead > 0) {
        inputStream->buffer.position += bytesRead;
        return bytesRead;
    }
    else return -1;
}

static int XInputStream_recvmsg(XInputStream* inputStream) {
    int remaining = inputStream->buffer.limit - inputStream->buffer.position;
    int numFds = 0;
    int bytesRead = recv_fds(inputStream->fd, ancillaryFds, &numFds, inputStream->buffer.data + inputStream->buffer.position, remaining);
    if (bytesRead > 0) {
        for (int i = 0; i < numFds; i++) IntArray_add(&inputStream->ancillaryFds, ancillaryFds[i]);
        inputStream->buffer.position += bytesRead;
        return bytesRead;
    }
    else return -1;
}

static int XInputStream_readMoreData(XInputStream* inputStream, jboolean canReceiveAncillaryMessages) {
    if (inputStream->activeBuffer.data) {
        if (inputStream->activeBuffer.position >= inputStream->activeBuffer.limit) {
            inputStream->buffer.position = 0;
            inputStream->buffer.limit = inputStream->buffer.capacity;
        }
        else if (inputStream->activeBuffer.position > 0) {
            int newLimit = inputStream->buffer.position;
            inputStream->buffer.position = inputStream->activeBuffer.position;
            inputStream->buffer.limit = newLimit;
            compactInputBuffer(&inputStream->buffer);
        }

        inputStream->activeBuffer.data = NULL;
    }

    growInputBufferIfNecessary(&inputStream->buffer);
    int bytesRead = canReceiveAncillaryMessages ? XInputStream_recvmsg(inputStream) : XInputStream_read(inputStream);

    if (bytesRead > 0) {
        activateInputBuffer(&inputStream->buffer, &inputStream->activeBuffer);
        inputStream->buffer.limit = inputStream->buffer.capacity;
    }
    return bytesRead;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_nativeAllocate(JNIEnv *env, jobject obj, jint fd,
                                                         jint initialCapacity) {
    XInputStream* inputStream = XInputStream_allocate(fd, initialCapacity);
    return (jlong)inputStream;
}

JNIEXPORT jbyte JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_readByte(jlong nativePtr) {
    XInputStream* inputStream = (XInputStream*)nativePtr;
    return *(jbyte*)(inputStream->activeBuffer.data + inputStream->activeBuffer.position++);
}

JNIEXPORT jshort JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_readShort(jlong nativePtr) {
    XInputStream* inputStream = (XInputStream*)nativePtr;
    jshort value = *(jshort*)(inputStream->activeBuffer.data + inputStream->activeBuffer.position);
    inputStream->activeBuffer.position += 2;
    return value;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_readInt(jlong nativePtr) {
    XInputStream* inputStream = (XInputStream*)nativePtr;
    jint value = *(jint*)(inputStream->activeBuffer.data + inputStream->activeBuffer.position);
    inputStream->activeBuffer.position += 4;
    return value;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_readLong(jlong nativePtr) {
    XInputStream* inputStream = (XInputStream*)nativePtr;
    jlong value = *(jlong*)(inputStream->activeBuffer.data + inputStream->activeBuffer.position);
    inputStream->activeBuffer.position += 8;
    return value;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_skip(jlong nativePtr, jint length) {
    ((XInputStream*)nativePtr)->activeBuffer.position += length;
}

JNIEXPORT jobject JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_readByteBuffer(JNIEnv *env, jobject obj,
                                                         jlong nativePtr, jint length) {
    XInputStream* inputStream = (XInputStream*)nativePtr;
    jobject byteBuffer = (*env)->NewDirectByteBuffer(env, inputStream->activeBuffer.data + inputStream->activeBuffer.position, length);
    inputStream->activeBuffer.position += length;
    return byteBuffer;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_available(jlong nativePtr) {
    XInputStream* inputStream = (XInputStream*)nativePtr;
    return inputStream->activeBuffer.limit - inputStream->activeBuffer.position;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_readMoreData(jlong nativePtr, jboolean canReceiveAncillaryMessages) {
    return XInputStream_readMoreData((XInputStream*)nativePtr, canReceiveAncillaryMessages);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_getActivePosition(jlong nativePtr) {
    return ((XInputStream*)nativePtr)->activeBuffer.position;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_setActivePosition(jlong nativePtr, jint activePosition) {
    ((XInputStream*)nativePtr)->activeBuffer.position = activePosition;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_getAncillaryFd(jlong nativePtr) {
    XInputStream* inputStream = (XInputStream*)nativePtr;
    return inputStream->ancillaryFds.size > 0 ? IntArray_removeAt(&inputStream->ancillaryFds, 0) : -1;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xconnector_XInputStream_destroy(jlong nativePtr) {
    XInputStream_destroy((XInputStream*)nativePtr);
}