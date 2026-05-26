/*
 * mock_ndk/ahb_internal.h — Internal AHardwareBuffer struct definition for tests.
 *
 * This header defines the concrete AHardwareBuffer struct used by the mock
 * implementation. It must be included BEFORE android/hardware_buffer.h in
 * any translation unit that needs to allocate or inspect AHardwareBuffer objects.
 *
 * ahb_bridge.c only uses AHardwareBuffer as an opaque pointer and never
 * includes this header — it only sees the forward declaration from
 * android/hardware_buffer.h.
 */
#pragma once

#include <stdint.h>

struct AHardwareBuffer {
    uint32_t width;
    uint32_t height;
    uint32_t layers;
    uint32_t format;
    uint64_t usage;
    int      refcount;
};
