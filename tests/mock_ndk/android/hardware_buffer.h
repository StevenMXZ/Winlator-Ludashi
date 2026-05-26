/*
 * mock_ndk/android/hardware_buffer.h — Controllable mock for AHardwareBuffer NDK API.
 *
 * In test mode (AHB_BRIDGE_TEST_MODE=1), the functions delegate to
 * mock_ahb_* function pointers that the test can swap out to inject failures.
 */
#pragma once

#include <stdint.h>

/* ── AHardwareBuffer_Desc ─────────────────────────────────────────────────── */
typedef struct AHardwareBuffer_Desc {
    uint32_t width;
    uint32_t height;
    uint32_t layers;
    uint32_t format;
    uint64_t usage;
    uint32_t stride;
    uint32_t rfu0;
    uint64_t rfu1;
} AHardwareBuffer_Desc;

/* Opaque handle — forward declaration only in the public header */
typedef struct AHardwareBuffer AHardwareBuffer;

/* ── Mock control (only compiled in test mode) ───────────────────────────── */
#ifdef AHB_BRIDGE_TEST_MODE

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Function pointers that the test replaces to inject failures.
 * Default implementations (set in mock_ndk_init()) behave like the real NDK.
 */
extern int  (*mock_AHardwareBuffer_allocate)(const AHardwareBuffer_Desc*, AHardwareBuffer**);
extern void (*mock_AHardwareBuffer_release)(AHardwareBuffer*);
extern void (*mock_AHardwareBuffer_acquire)(AHardwareBuffer*);
extern int  (*mock_AHardwareBuffer_sendHandleToUnixSocket)(const AHardwareBuffer*, int);
extern int  (*mock_AHardwareBuffer_recvHandleFromUnixSocket)(int, AHardwareBuffer**);

/* Call this once before tests to set up default (passing) implementations. */
void mock_ndk_init(void);

/* Convenience: reset all mocks to their default (passing) implementations. */
void mock_ndk_reset(void);

#ifdef __cplusplus
}
#endif

/* ── Macro shims: redirect NDK calls to mock function pointers ─────────────── */
#define AHardwareBuffer_allocate(desc, out)                  mock_AHardwareBuffer_allocate((desc), (out))
#define AHardwareBuffer_release(ahb)                         mock_AHardwareBuffer_release((ahb))
#define AHardwareBuffer_acquire(ahb)                         mock_AHardwareBuffer_acquire((ahb))
#define AHardwareBuffer_sendHandleToUnixSocket(ahb, fd)      mock_AHardwareBuffer_sendHandleToUnixSocket((ahb), (fd))
#define AHardwareBuffer_recvHandleFromUnixSocket(fd, out)    mock_AHardwareBuffer_recvHandleFromUnixSocket((fd), (out))

#else /* !AHB_BRIDGE_TEST_MODE — real NDK declarations */

int  AHardwareBuffer_allocate(const AHardwareBuffer_Desc* desc, AHardwareBuffer** outBuffer);
void AHardwareBuffer_release(AHardwareBuffer* buffer);
void AHardwareBuffer_acquire(AHardwareBuffer* buffer);
int  AHardwareBuffer_sendHandleToUnixSocket(const AHardwareBuffer* buffer, int socketFd);
int  AHardwareBuffer_recvHandleFromUnixSocket(int socketFd, AHardwareBuffer** outBuffer);

#endif /* AHB_BRIDGE_TEST_MODE */
