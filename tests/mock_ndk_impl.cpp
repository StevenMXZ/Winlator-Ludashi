/*
 * mock_ndk_impl.cpp — Implementations of the mock NDK function pointers and
 * log capture infrastructure used by the AHB bridge host-side tests.
 *
 * Feature: direct-android-compositing
 */

#include <cstdlib>
#include <cstring>
#include <cstdio>

/*
 * Include the internal AHardwareBuffer struct definition BEFORE the mock
 * hardware_buffer.h so the struct is fully defined when the typedef is seen.
 */
#include "mock_ndk/ahb_internal.h"

/* Pull in the mock headers (with AHB_BRIDGE_TEST_MODE defined by the build) */
#include "mock_ndk/android/hardware_buffer.h"
#include "mock_ndk/android/log.h"
#include "mock_ndk/sync/sync.h"

/* ── Log capture state ──────────────────────────────────────────────────── */
MockLogEntry mock_log_entries[MOCK_LOG_MAX_ENTRIES];
int          mock_log_count = 0;

extern "C" void mock_log_reset(void) {
    mock_log_count = 0;
    memset(mock_log_entries, 0, sizeof(mock_log_entries));
}

extern "C" int mock_log_contains(const char* tag, const char* substring) {
    for (int i = 0; i < mock_log_count; ++i) {
        if (strcmp(mock_log_entries[i].tag, tag) == 0 &&
            strstr(mock_log_entries[i].message, substring) != nullptr) {
            return 1;
        }
    }
    return 0;
}

extern "C" int mock_log_contains_with_priority(int priority, const char* tag, const char* substring) {
    for (int i = 0; i < mock_log_count; ++i) {
        if (mock_log_entries[i].priority == priority &&
            strcmp(mock_log_entries[i].tag, tag) == 0 &&
            strstr(mock_log_entries[i].message, substring) != nullptr) {
            return 1;
        }
    }
    return 0;
}

/* ── Default (passing) AHardwareBuffer implementations ─────────────────── */

/*
 * A minimal heap-allocated AHardwareBuffer stand-in.
 * The real NDK type is opaque; for testing we just need a non-null pointer.
 * The struct is defined at the top of this file before the mock headers.
 */

static int default_AHardwareBuffer_allocate(
        const AHardwareBuffer_Desc* desc, AHardwareBuffer** out) {
    AHardwareBuffer* ahb = (AHardwareBuffer*)malloc(sizeof(AHardwareBuffer));
    if (!ahb) return -1;
    ahb->width    = desc->width;
    ahb->height   = desc->height;
    ahb->layers   = desc->layers;
    ahb->format   = desc->format;
    ahb->usage    = desc->usage;
    ahb->refcount = 1;
    *out = ahb;
    return 0;
}

static void default_AHardwareBuffer_release(AHardwareBuffer* ahb) {
    if (!ahb) return;
    ahb->refcount--;
    if (ahb->refcount <= 0) free(ahb);
}

static void default_AHardwareBuffer_acquire(AHardwareBuffer* ahb) {
    if (ahb) ahb->refcount++;
}

static int default_AHardwareBuffer_sendHandleToUnixSocket(
        const AHardwareBuffer* /*ahb*/, int /*fd*/) {
    return 0; /* success */
}

static int default_AHardwareBuffer_recvHandleFromUnixSocket(
        int /*fd*/, AHardwareBuffer** out) {
    /* Return a freshly allocated buffer as a stand-in */
    AHardwareBuffer* ahb = (AHardwareBuffer*)malloc(sizeof(AHardwareBuffer));
    if (!ahb) return -1;
    memset(ahb, 0, sizeof(*ahb));
    ahb->refcount = 1;
    *out = ahb;
    return 0;
}

/* ── Default sync_wait implementation ──────────────────────────────────── */
static int default_sync_wait(int /*fd*/, int /*timeout*/) {
    return 0; /* success */
}

/* ── Function pointer definitions ───────────────────────────────────────── */
int  (*mock_AHardwareBuffer_allocate)(const AHardwareBuffer_Desc*, AHardwareBuffer**)
    = default_AHardwareBuffer_allocate;

void (*mock_AHardwareBuffer_release)(AHardwareBuffer*)
    = default_AHardwareBuffer_release;

void (*mock_AHardwareBuffer_acquire)(AHardwareBuffer*)
    = default_AHardwareBuffer_acquire;

int  (*mock_AHardwareBuffer_sendHandleToUnixSocket)(const AHardwareBuffer*, int)
    = default_AHardwareBuffer_sendHandleToUnixSocket;

int  (*mock_AHardwareBuffer_recvHandleFromUnixSocket)(int, AHardwareBuffer**)
    = default_AHardwareBuffer_recvHandleFromUnixSocket;

int  (*mock_sync_wait)(int, int)
    = default_sync_wait;

/* ── Init / reset helpers ───────────────────────────────────────────────── */
extern "C" void mock_ndk_init(void) {
    mock_ndk_reset();
}

extern "C" void mock_ndk_reset(void) {
    mock_AHardwareBuffer_allocate             = default_AHardwareBuffer_allocate;
    mock_AHardwareBuffer_release              = default_AHardwareBuffer_release;
    mock_AHardwareBuffer_acquire              = default_AHardwareBuffer_acquire;
    mock_AHardwareBuffer_sendHandleToUnixSocket   = default_AHardwareBuffer_sendHandleToUnixSocket;
    mock_AHardwareBuffer_recvHandleFromUnixSocket = default_AHardwareBuffer_recvHandleFromUnixSocket;
    mock_sync_wait                            = default_sync_wait;
    mock_log_reset();
}
