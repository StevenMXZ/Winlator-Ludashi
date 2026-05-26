/*
 * mock_ndk/sync/sync.h — Controllable mock for Android sync fence API.
 *
 * In test mode, sync_wait delegates to a replaceable function pointer
 * so tests can inject timeout/error conditions.
 */
#pragma once

#ifdef AHB_BRIDGE_TEST_MODE

#ifdef __cplusplus
extern "C" {
#endif

/* Replaceable mock: default returns 0 (success). */
extern int (*mock_sync_wait)(int fd, int timeout);

#ifdef __cplusplus
}
#endif

#define sync_wait(fd, timeout)  mock_sync_wait((fd), (timeout))

#else /* !AHB_BRIDGE_TEST_MODE */

int sync_wait(int fd, int timeout);

#endif /* AHB_BRIDGE_TEST_MODE */
