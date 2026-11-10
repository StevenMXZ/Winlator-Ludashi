#pragma once

#include "base/types.h"
#include "core/xresource.h"

namespace xorg {

class Visual final : public XResource {
public:
    u8 visual_class;
    u8 depth;
    u8 bpp;
    u16 colormap_entries;
    s32 blue_mask;
    s32 green_mask;
    s32 red_mask;

    Visual(u32 id, u8 depth, u8 bpp, s32 blue_mask, s32 green_mask, s32 red_mask)
            : XResource(XResourceType::Visual, id)
            , visual_class(4)
            , depth(depth)
            , bpp(bpp)
            , colormap_entries(256)
            , blue_mask(blue_mask)
            , green_mask(green_mask)
            , red_mask(red_mask) {}
};

}  // namespace xorg