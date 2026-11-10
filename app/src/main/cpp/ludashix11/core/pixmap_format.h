#pragma once

#include "base/types.h"

namespace xorg {

struct PixmapFormat {
    u8 depth;
    u8 bpp;
    u8 scanline_pad;
};

}  // namespace xorg