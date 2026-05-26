/*
 * ahb_bridge.c — JNI bridge for AHardwareBuffer operations used by AHardwareBufferPool.
 *
 * Compiled into libwinlator.so. Provides five JNI functions:
 *   nativeCreateBuffer            — allocate an AHardwareBuffer
 *   nativeDestroyBuffer           — release an AHardwareBuffer
 *   nativeSendBufferToSocket      — send an AHB handle over a Unix socket
 *   nativeReceiveBufferFromSocket — receive an AHB handle from a Unix socket
 *   nativeWaitFence               — wait on a sync fence fd, then close it
 *
 * All functions return sentinel values (0 for pointers, -1 for fds/status) on
 * failure and log with tag "AHB_Bridge".
 */

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <poll.h>
#include <unistd.h>

#define LOG_TAG "AHB_Bridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

/* ---------------------------------------------------------------------------
 * nativeCreateBuffer
 *
 * Allocates an AHardwareBuffer with the given dimensions, format, and usage.
 * Returns the opaque pointer cast to jlong, or 0 on failure.
 * --------------------------------------------------------------------------- */
JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeCreateBuffer(
        JNIEnv *env, jclass cls,
        jint width, jint height, jint format, jlong usage)
{
    (void)env; (void)cls;

    AHardwareBuffer_Desc desc = {
        .width  = (uint32_t)width,
        .height = (uint32_t)height,
        .layers = 1,
        .format = (uint32_t)format,
        .usage  = (uint64_t)usage,
        .stride = 0,  /* filled in by allocate */
        .rfu0   = 0,
        .rfu1   = 0,
    };

    AHardwareBuffer *ahb = NULL;
    int ret = AHardwareBuffer_allocate(&desc, &ahb);
    if (ret != 0 || ahb == NULL) {
        LOGE("nativeCreateBuffer: AHardwareBuffer_allocate failed (ret=%d, w=%d, h=%d, fmt=%d)",
             ret, width, height, format);
        return 0;
    }

    return (jlong)(uintptr_t)ahb;
}

/* ---------------------------------------------------------------------------
 * nativeDestroyBuffer
 *
 * Releases the AHardwareBuffer at the given pointer.
 * --------------------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeDestroyBuffer(
        JNIEnv *env, jclass cls, jlong ptr)
{
    (void)env; (void)cls;

    if (ptr == 0) {
        LOGW("nativeDestroyBuffer: called with null pointer, ignoring");
        return;
    }

    AHardwareBuffer *ahb = (AHardwareBuffer *)(uintptr_t)ptr;
    AHardwareBuffer_release(ahb);
}

/* ---------------------------------------------------------------------------
 * nativeSendBufferToSocket
 *
 * Sends the AHardwareBuffer handle to a Unix socket fd using
 * AHardwareBuffer_sendHandleToUnixSocket.
 * Returns 0 on success, -1 on failure.
 * --------------------------------------------------------------------------- */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeSendBufferToSocket(
        JNIEnv *env, jclass cls, jlong ptr, jint fd)
{
    (void)env; (void)cls;

    if (ptr == 0) {
        LOGE("nativeSendBufferToSocket: null buffer pointer");
        return -1;
    }

    AHardwareBuffer *ahb = (AHardwareBuffer *)(uintptr_t)ptr;
    int ret = AHardwareBuffer_sendHandleToUnixSocket(ahb, (int)fd);
    if (ret != 0) {
        LOGE("nativeSendBufferToSocket: AHardwareBuffer_sendHandleToUnixSocket failed (ret=%d, fd=%d)",
             ret, fd);
        return -1;
    }

    return 0;
}

/* ---------------------------------------------------------------------------
 * nativeReceiveBufferFromSocket
 *
 * Receives an AHardwareBuffer handle from a Unix socket fd using
 * AHardwareBuffer_recvHandleFromUnixSocket.
 * Returns the pointer as jlong, or 0 on failure.
 * --------------------------------------------------------------------------- */
JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeReceiveBufferFromSocket(
        JNIEnv *env, jclass cls, jint fd)
{
    (void)env; (void)cls;

    AHardwareBuffer *ahb = NULL;
    int ret = AHardwareBuffer_recvHandleFromUnixSocket((int)fd, &ahb);
    if (ret != 0 || ahb == NULL) {
        LOGE("nativeReceiveBufferFromSocket: AHardwareBuffer_recvHandleFromUnixSocket failed (ret=%d, fd=%d)",
             ret, fd);
        return 0;
    }

    /* Caller owns a reference; bump the refcount so it survives the socket message. */
    AHardwareBuffer_acquire(ahb);
    return (jlong)(uintptr_t)ahb;
}

/* ---------------------------------------------------------------------------
 * nativeWaitFence
 *
 * Waits for the sync fence fd to signal, with a timeout of timeoutMs
 * milliseconds. Closes fd regardless of outcome (success, timeout, or error).
 * Returns 0 on success, -1 on timeout or error.
 * --------------------------------------------------------------------------- */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeWaitFence(
        JNIEnv *env, jclass cls, jint fd, jint timeoutMs)
{
    (void)env; (void)cls;

    if (fd < 0) {
        /* No fence to wait on — treat as already signalled. */
        return 0;
    }

    /* Use poll() to wait for the sync fence fd to become readable, which
     * is equivalent to sync_wait() from <sync/sync.h> but avoids the NDK
     * header that is not in the standard sysroot include path. */
    struct pollfd pfd = { .fd = (int)fd, .events = POLLIN };
    int ret = poll(&pfd, 1, (int)timeoutMs);
    /* Close fd regardless of outcome, as documented. */
    close((int)fd);

    if (ret == 0) {
        /* Timeout */
        LOGE("nativeWaitFence: timed out waiting for fence (fd=%d, timeoutMs=%d)",
             fd, timeoutMs);
        return -1;
    } else if (ret < 0) {
        LOGE("nativeWaitFence: poll failed (fd=%d, timeoutMs=%d, errno=%d)",
             fd, timeoutMs, errno);
        return -1;
    }

    return 0;
}
