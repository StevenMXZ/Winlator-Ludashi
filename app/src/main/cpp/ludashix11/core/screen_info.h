#pragma once

#include <fmt/format.h>
#include "base/types.h"

namespace xorg {

struct ScreenInfo {
    ScreenInfo(u16 width, u16 height, u16 pixel_per_mm)
            : width(width), height(height), pixel_per_mm(pixel_per_mm) {}

    u16 GetWidthInMillimeters() const { return width / pixel_per_mm; }

    u16 GetHeightInMillimeters() const { return height / pixel_per_mm; }

    const u16 width;
    const u16 height;
    const u16 pixel_per_mm;
};

}  // namespace xorg

template <> struct fmt::formatter<xorg::ScreenInfo> : formatter<std::string> {
    template <typename FormatContext>
    auto format(const xorg::ScreenInfo& screen_info, FormatContext& ctx) const {
        return format_to(ctx.out(), "{}x{}", screen_info.width, screen_info.height);
    }
};