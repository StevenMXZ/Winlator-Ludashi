#include "core/drawable.h"
#include "core/pixmap.h"
#include "core/xscreen.h"

namespace xorg {

Pixmap::Pixmap(XScreen& screen, u32 id, u16 width, u16 height, u8 depth)
        : XResource(XResourceType::Pixmap, id), width(width), height(height), depth(depth) {
    drawable = std::move(screen.CreateDrawable(width, height, depth));
}

Pixmap::Pixmap(XScreen& screen, u32 id, u16 width, u16 height, AHardwareBuffer* buffer)
        : XResource(XResourceType::Pixmap, id), width(width), height(height), depth(32) {
    is_gpu_pixmap = true;
    drawable = std::move(screen.CreateDrawable(width, height, buffer));
}

Pixmap::~Pixmap() {}

}  // namespace xorg