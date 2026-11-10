#include "core/drawable.h"

namespace xorg {

Drawable::Drawable(u16 width, u16 height, u8 depth, AHardwareBuffer* buffer)
        : width(width), height(height), depth(depth), buffer(buffer) {}

Drawable::~Drawable() {
    if (buffer) {
        AHardwareBuffer_release(buffer);
    }
}

}  // namespace xorg
