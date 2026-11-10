#pragma once

#include <vector>

#include "base/types.h"

struct CursorFont {
    const u8* data;
    u16 width;
    u16 height;
    s16 xhot;
    s16 yhot;

    std::vector<u8> ToRGBA() const;
};

const CursorFont& GetXCursorFont(u32 id);

bool DecompressGrayscale(u32 width, u32 height, const u8* compressed_data, u8* decompressed_data);