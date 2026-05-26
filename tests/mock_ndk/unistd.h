/*
 * mock_ndk/unistd.h — Minimal unistd.h stub for Windows host-side testing.
 *
 * On Linux/macOS, the real unistd.h is used. On Windows (MSVC), this stub
 * provides the close() function needed by ahb_bridge.c.
 *
 * ahb_bridge.c calls close(fd) after sync_wait to close the fence fd.
 * In test mode, the fd values are arbitrary integers (not real file descriptors),
 * so we provide a no-op close() that doesn't actually close anything.
 */
#pragma once

#ifdef _WIN32

#ifdef AHB_BRIDGE_TEST_MODE

/*
 * In test mode, close() is a no-op because the test passes arbitrary integer
 * fd values that are not real file descriptors.
 *
 * We define close as a macro BEFORE including any system headers that might
 * define it, so our definition takes precedence.
 */
#define close(fd) ((void)(fd), 0)

#else /* !AHB_BRIDGE_TEST_MODE */

/* Outside test mode, map close() to the Windows _close() */
#include <io.h>
#ifndef close
#  define close(fd) _close(fd)
#endif

#endif /* AHB_BRIDGE_TEST_MODE */

#endif /* _WIN32 */
