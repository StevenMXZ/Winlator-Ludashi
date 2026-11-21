#pragma once

#include <span>
#include <vector>
#include "base/native_types.h"

namespace ludashi::x11 {

struct WindowMetadata {
    u32 type;
    u8 format;
    std::vector<u8> bytes;

    std::span<const u8> GetData() const { return bytes; }
};

} // namespace ludashi::x11
