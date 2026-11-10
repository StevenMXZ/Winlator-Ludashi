#pragma once
#include "../../stubs/android/hardware_buffer.h"
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM 1
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE 256
inline int AHardwareBuffer_allocate(const AHardwareBuffer_Desc* description, AHardwareBuffer** output) {
    auto* buffer = new AHardwareBuffer;
    buffer->desc = *description; buffer->desc.stride = description->width + 4;
    buffer->pixels.resize(size_t(buffer->desc.stride) * description->height * 4); *output = buffer; return 0;
}
