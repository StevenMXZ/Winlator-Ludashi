/*
 * prop13_jni_bridge_error_sentinel.cpp
 *
 * JNI Bridge Error Sentinel tests
 * ────────────────────────────────
 * For each JNI function, inject NDK failure via mock, verify sentinel returned
 * and log message contains "AHB_Bridge".
 *
 * Testing framework: rapidcheck + GoogleTest
 * Minimum iterations: 100 (configured via RC_PARAMS env var or rapidcheck defaults)
 */

#include <gtest/gtest.h>
#include <rapidcheck.h>
#include <rapidcheck/gtest.h>

#include <cstdlib>
#include <cstring>
#include <climits>

/* ── Pull in mock NDK headers ─────────────────────────────────────────────── */
#include "mock_ndk/ahb_internal.h"   /* AHardwareBuffer struct (for test-side alloc) */
#include "mock_ndk/android/hardware_buffer.h"
#include "mock_ndk/android/log.h"
#include "mock_ndk/sync/sync.h"

/* ── Forward-declare the JNI functions under test ────────────────────────── */
/*
 * ahb_bridge.c is compiled into this test binary. We declare the JNI
 * function signatures here so we can call them directly without going
 * through the JVM.
 *
 * JNIEnv* and jclass are both void* in our minimal jni.h, so passing
 * nullptr is safe — ahb_bridge.c casts them to (void) immediately.
 */
#include "mock_ndk/jni.h"

extern "C" {

jlong Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeCreateBuffer(
        JNIEnv* env, jclass cls,
        jint width, jint height, jint format, jlong usage);

void Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeDestroyBuffer(
        JNIEnv* env, jclass cls, jlong ptr);

jint Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeSendBufferToSocket(
        JNIEnv* env, jclass cls, jlong ptr, jint fd);

jlong Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeReceiveBufferFromSocket(
        JNIEnv* env, jclass cls, jint fd);

jint Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeWaitFence(
        JNIEnv* env, jclass cls, jint fd, jint timeoutMs);

} /* extern "C" */

/* ── Convenience aliases ─────────────────────────────────────────────────── */
static JNIEnv* const kEnv = nullptr;
static jclass  const kCls = nullptr;

/* ── Test fixture: resets all mocks before each test ─────────────────────── */
class AhbBridgeTest : public ::testing::Test {
protected:
    void SetUp() override {
        mock_ndk_reset();
    }
    void TearDown() override {
        mock_ndk_reset();
    }
};

/* ═══════════════════════════════════════════════════════════════════════════
 * For any (width, height, format, usage), when AHardwareBuffer_allocate is
 * injected to fail, nativeCreateBuffer SHALL return 0 and SHALL log with
 * tag "AHB_Bridge".
 * ═══════════════════════════════════════════════════════════════════════════ */
RC_GTEST_FIXTURE_PROP(AhbBridgeTest, CreateBuffer_AllocateFailure_ReturnsSentinel, ()) {
    const jint  width  = *rc::gen::inRange<jint>(1, 4096);
    const jint  height = *rc::gen::inRange<jint>(1, 4096);
    const jint  format = *rc::gen::inRange<jint>(1, 32);
    const jlong usage  = *rc::gen::inRange<jlong>(0LL, (jlong)0xFFFFFFFFLL);

    mock_AHardwareBuffer_allocate = [](const AHardwareBuffer_Desc*, AHardwareBuffer**) -> int {
        return -1;
    };
    mock_log_reset();

    jlong result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeCreateBuffer(
            kEnv, kCls, width, height, format, usage);

    RC_ASSERT(result == 0);
    RC_ASSERT(mock_log_contains("AHB_Bridge", "") == 1);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * Even if allocate returns 0 (success code) but sets *out = NULL, the
 * function must still return the sentinel 0 and log.
 * ═══════════════════════════════════════════════════════════════════════════ */
RC_GTEST_FIXTURE_PROP(AhbBridgeTest, CreateBuffer_NullBufferFromAllocate_ReturnsSentinel, ()) {
    const jint  width  = *rc::gen::inRange<jint>(1, 4096);
    const jint  height = *rc::gen::inRange<jint>(1, 4096);
    const jint  format = *rc::gen::inRange<jint>(1, 32);
    const jlong usage  = *rc::gen::inRange<jlong>(0LL, (jlong)0xFFFFFFFFLL);

    mock_AHardwareBuffer_allocate = [](const AHardwareBuffer_Desc*, AHardwareBuffer** out) -> int {
        *out = nullptr;
        return 0;
    };
    mock_log_reset();

    jlong result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeCreateBuffer(
            kEnv, kCls, width, height, format, usage);

    RC_ASSERT(result == 0);
    RC_ASSERT(mock_log_contains("AHB_Bridge", "") == 1);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeSendBufferToSocket returns -1 on NDK failure
 *
 * For any (ptr, fd), when AHardwareBuffer_sendHandleToUnixSocket fails,
 * nativeSendBufferToSocket SHALL return -1 and log with tag "AHB_Bridge".
 * ═══════════════════════════════════════════════════════════════════════════ */
RC_GTEST_FIXTURE_PROP(AhbBridgeTest, SendBufferToSocket_SendFailure_ReturnsSentinel, ()) {
    AHardwareBuffer* ahb = nullptr;
    {
        AHardwareBuffer_Desc desc{};
        desc.width = 64; desc.height = 64; desc.layers = 1;
        desc.format = 1; desc.usage = 3;
        mock_AHardwareBuffer_allocate(&desc, &ahb);
    }
    RC_PRE(ahb != nullptr);

    const jlong ptr = (jlong)(uintptr_t)ahb;
    const jint  fd  = *rc::gen::inRange<jint>(0, 1023);

    mock_AHardwareBuffer_sendHandleToUnixSocket = [](const AHardwareBuffer*, int) -> int {
        return -1;
    };
    mock_log_reset();

    jint result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeSendBufferToSocket(
            kEnv, kCls, ptr, fd);

    RC_ASSERT(result == -1);
    RC_ASSERT(mock_log_contains("AHB_Bridge", "") == 1);

    mock_AHardwareBuffer_release(ahb);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeSendBufferToSocket returns -1 on null pointer input
 *
 * When ptr == 0, nativeSendBufferToSocket SHALL return -1 and log with
 * tag "AHB_Bridge" (null-pointer guard path).
 * ═══════════════════════════════════════════════════════════════════════════ */
RC_GTEST_FIXTURE_PROP(AhbBridgeTest, SendBufferToSocket_NullPtr_ReturnsSentinel, ()) {
    const jint fd = *rc::gen::inRange<jint>(0, 1023);
    mock_log_reset();

    jint result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeSendBufferToSocket(
            kEnv, kCls, (jlong)0, fd);

    RC_ASSERT(result == -1);
    RC_ASSERT(mock_log_contains("AHB_Bridge", "") == 1);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeReceiveBufferFromSocket returns 0 on NDK failure
 *
 * For any fd, when AHardwareBuffer_recvHandleFromUnixSocket fails,
 * nativeReceiveBufferFromSocket SHALL return 0 and log with tag "AHB_Bridge".
 * ═══════════════════════════════════════════════════════════════════════════ */
RC_GTEST_FIXTURE_PROP(AhbBridgeTest, ReceiveBufferFromSocket_RecvFailure_ReturnsSentinel, ()) {
    const jint fd = *rc::gen::inRange<jint>(0, 1023);

    mock_AHardwareBuffer_recvHandleFromUnixSocket = [](int, AHardwareBuffer**) -> int {
        return -1;
    };
    mock_log_reset();

    jlong result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeReceiveBufferFromSocket(
            kEnv, kCls, fd);

    RC_ASSERT(result == 0);
    RC_ASSERT(mock_log_contains("AHB_Bridge", "") == 1);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeReceiveBufferFromSocket returns 0 when recv yields null
 *
 * When recvHandleFromUnixSocket "succeeds" but sets *out = NULL, the
 * function must still return 0 and log.
 * ═══════════════════════════════════════════════════════════════════════════ */
RC_GTEST_FIXTURE_PROP(AhbBridgeTest, ReceiveBufferFromSocket_NullBufferFromRecv_ReturnsSentinel, ()) {
    const jint fd = *rc::gen::inRange<jint>(0, 1023);

    mock_AHardwareBuffer_recvHandleFromUnixSocket = [](int, AHardwareBuffer** out) -> int {
        *out = nullptr;
        return 0;
    };
    mock_log_reset();

    jlong result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeReceiveBufferFromSocket(
            kEnv, kCls, fd);

    RC_ASSERT(result == 0);
    RC_ASSERT(mock_log_contains("AHB_Bridge", "") == 1);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeWaitFence returns -1 on sync_wait failure
 *
 * For any (fd >= 0, timeoutMs), when sync_wait fails (returns < 0),
 * nativeWaitFence SHALL return -1 and log with tag "AHB_Bridge".
 * ═══════════════════════════════════════════════════════════════════════════ */
RC_GTEST_FIXTURE_PROP(AhbBridgeTest, WaitFence_SyncWaitFailure_ReturnsSentinel, ()) {
    /* fd must be >= 0 to reach the sync_wait call (fd < 0 is the "no fence" fast path) */
    const jint fd        = *rc::gen::inRange<jint>(0, 1023);
    const jint timeoutMs = *rc::gen::inRange<jint>(0, 10000);

    mock_sync_wait = [](int, int) -> int {
        return -1;
    };
    mock_log_reset();

    jint result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeWaitFence(
            kEnv, kCls, fd, timeoutMs);

    RC_ASSERT(result == -1);
    RC_ASSERT(mock_log_contains("AHB_Bridge", "") == 1);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeWaitFence with fd < 0 returns 0 (no-fence fast path)
 *
 * This is not a failure path — it's the "no fence to wait on" case.
 * Verifying it doesn't log an error and returns success.
 * ═══════════════════════════════════════════════════════════════════════════ */
TEST_F(AhbBridgeTest, WaitFence_NegativeFd_ReturnsSuccess) {
    mock_log_reset();

    jint result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeWaitFence(
            kEnv, kCls, (jint)-1, (jint)16);

    EXPECT_EQ(result, 0);
    EXPECT_EQ(mock_log_count, 0);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeCreateBuffer succeeds with valid inputs (sanity check)
 * ═══════════════════════════════════════════════════════════════════════════ */
TEST_F(AhbBridgeTest, CreateBuffer_ValidInputs_ReturnsNonZero) {
    mock_log_reset();

    jlong result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeCreateBuffer(
            kEnv, kCls, (jint)1920, (jint)1080, (jint)1, (jlong)0x33);

    EXPECT_NE(result, 0);
    EXPECT_EQ(mock_log_count, 0);

    Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeDestroyBuffer(
            kEnv, kCls, result);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeSendBufferToSocket succeeds with valid inputs (sanity check)
 * ═══════════════════════════════════════════════════════════════════════════ */
TEST_F(AhbBridgeTest, SendBufferToSocket_ValidInputs_ReturnsZero) {
    jlong ptr = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeCreateBuffer(
            kEnv, kCls, (jint)64, (jint)64, (jint)1, (jlong)0x33);
    ASSERT_NE(ptr, 0);

    mock_log_reset();

    jint result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeSendBufferToSocket(
            kEnv, kCls, ptr, (jint)5);

    EXPECT_EQ(result, 0);
    EXPECT_EQ(mock_log_count, 0);

    Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeDestroyBuffer(kEnv, kCls, ptr);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeReceiveBufferFromSocket succeeds with valid inputs
 * ═══════════════════════════════════════════════════════════════════════════ */
TEST_F(AhbBridgeTest, ReceiveBufferFromSocket_ValidInputs_ReturnsNonZero) {
    mock_log_reset();

    jlong result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeReceiveBufferFromSocket(
            kEnv, kCls, (jint)5);

    EXPECT_NE(result, 0);
    EXPECT_EQ(mock_log_count, 0);

    Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeDestroyBuffer(kEnv, kCls, result);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * nativeWaitFence succeeds with valid fd (sanity check)
 * ═══════════════════════════════════════════════════════════════════════════ */
TEST_F(AhbBridgeTest, WaitFence_ValidFd_ReturnsZero) {
    mock_log_reset();

    jint result = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeWaitFence(
            kEnv, kCls, (jint)5, (jint)16);

    EXPECT_EQ(result, 0);
    EXPECT_EQ(mock_log_count, 0);
}

/* ═══════════════════════════════════════════════════════════════════════════
 * Comprehensive: all five JNI functions log "AHB_Bridge" on failure
 *
 * Sweeps over all five functions and verifies the invariant holds for each
 * one with arbitrary inputs.
 * ═══════════════════════════════════════════════════════════════════════════ */
RC_GTEST_FIXTURE_PROP(AhbBridgeTest, AllFunctions_OnNdkFailure_LogTagIsAhbBridge, ()) {
    const int func_idx = *rc::gen::inRange<int>(0, 5);

    const jint  width   = *rc::gen::inRange<jint>(1, 4096);
    const jint  height  = *rc::gen::inRange<jint>(1, 4096);
    const jint  format  = *rc::gen::inRange<jint>(1, 32);
    const jlong usage   = *rc::gen::inRange<jlong>(0LL, (jlong)0xFFFFFFFFLL);
    const jint  fd      = *rc::gen::inRange<jint>(0, 1023);
    const jint  timeout = *rc::gen::inRange<jint>(0, 10000);

    mock_AHardwareBuffer_allocate = [](const AHardwareBuffer_Desc*, AHardwareBuffer**) -> int {
        return -1;
    };
    mock_AHardwareBuffer_sendHandleToUnixSocket = [](const AHardwareBuffer*, int) -> int {
        return -1;
    };
    mock_AHardwareBuffer_recvHandleFromUnixSocket = [](int, AHardwareBuffer**) -> int {
        return -1;
    };
    mock_sync_wait = [](int, int) -> int {
        return -1;
    };
    mock_log_reset();

    bool logged_ahb_bridge = false;
    bool sentinel_correct  = false;

    switch (func_idx) {
        case 0: {
            jlong r = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeCreateBuffer(
                    kEnv, kCls, width, height, format, usage);
            sentinel_correct  = (r == 0);
            logged_ahb_bridge = (mock_log_contains("AHB_Bridge", "") == 1);
            break;
        }
        case 1: {
            /* null ptr — guaranteed failure path */
            jint r = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeSendBufferToSocket(
                    kEnv, kCls, (jlong)0, fd);
            sentinel_correct  = (r == -1);
            logged_ahb_bridge = (mock_log_contains("AHB_Bridge", "") == 1);
            break;
        }
        case 2: {
            /* non-null ptr — NDK failure path; allocate a real buffer first */
            mock_AHardwareBuffer_allocate = [](const AHardwareBuffer_Desc* d, AHardwareBuffer** out) -> int {
                AHardwareBuffer* ahb = (AHardwareBuffer*)malloc(sizeof(AHardwareBuffer));
                if (!ahb) return -1;
                memset(ahb, 0, sizeof(*ahb));
                ahb->width = d->width; ahb->height = d->height;
                ahb->refcount = 1;
                *out = ahb;
                return 0;
            };
            AHardwareBuffer* ahb = nullptr;
            AHardwareBuffer_Desc desc{};
            desc.width = 64; desc.height = 64; desc.layers = 1;
            desc.format = 1; desc.usage = 3;
            mock_AHardwareBuffer_allocate(&desc, &ahb);
            mock_AHardwareBuffer_sendHandleToUnixSocket = [](const AHardwareBuffer*, int) -> int {
                return -1;
            };
            mock_log_reset();

            jlong ptr = (jlong)(uintptr_t)ahb;
            jint r = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeSendBufferToSocket(
                    kEnv, kCls, ptr, fd);
            sentinel_correct  = (r == -1);
            logged_ahb_bridge = (mock_log_contains("AHB_Bridge", "") == 1);
            if (ahb) free(ahb);
            break;
        }
        case 3: {
            jlong r = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeReceiveBufferFromSocket(
                    kEnv, kCls, fd);
            sentinel_correct  = (r == 0);
            logged_ahb_bridge = (mock_log_contains("AHB_Bridge", "") == 1);
            break;
        }
        case 4: {
            /* fd >= 0 to reach sync_wait */
            jint r = Java_com_winlator_cmod_renderer_AHardwareBufferPool_nativeWaitFence(
                    kEnv, kCls, fd, timeout);
            sentinel_correct  = (r == -1);
            logged_ahb_bridge = (mock_log_contains("AHB_Bridge", "") == 1);
            break;
        }
    }

    RC_ASSERT(sentinel_correct);
    RC_ASSERT(logged_ahb_bridge);
}
