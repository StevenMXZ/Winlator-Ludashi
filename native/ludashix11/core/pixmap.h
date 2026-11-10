#pragma once

#include <android/hardware_buffer.h>
#include "core/xresource.h"

namespace xorg {

class Drawable;
class XScreen;

class Pixmap final : public XResource {
public:
    Pixmap(XScreen& screen, u32 id, u16 width, u16 height, u8 depth);

    Pixmap(XScreen& screen, u32 id, u16 width, u16 height, AHardwareBuffer* buffer);

    ~Pixmap() override;

    Drawable* GetDrawable() const { return drawable.get(); }

    const sp<Drawable>& SharedDrawable() const { return drawable; }

    bool IsGPUPixmap() const { return is_gpu_pixmap; }

    const u16 width;
    const u16 height;
    const u8 depth;
    bool is_gpu_pixmap{};

private:
    sp<Drawable> drawable;
};

}  // namespace xorg