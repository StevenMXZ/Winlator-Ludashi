#pragma once
#include "../../stubs/android/hardware_buffer.h"
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM 1
#define AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM 2
#define AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY 2048
inline int testAllocations{}, testAllocationFailures{};
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE 256
inline int AHardwareBuffer_allocate(const AHardwareBuffer_Desc* description, AHardwareBuffer** output) {
    ++testAllocations;
    if ((std::getenv("TEST_REJECT_OVERLAY") && (description->usage & AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY)) ||
        (std::getenv("TEST_REJECT_RGBX") && description->format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM)) {
        ++testAllocationFailures;
        return -1;
    }
    auto* buffer = new AHardwareBuffer;
    buffer->desc = *description; buffer->desc.stride = description->width + 4;
    buffer->pixels.resize(size_t(buffer->desc.stride) * description->height * 4); *output = buffer; return 0;
}
