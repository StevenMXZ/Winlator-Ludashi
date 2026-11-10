#pragma once

#include <array>
#include <vector>

#include <X11/X.h>
#include <X11/Xmd.h>
#include <X11/Xprotostr.h>

#include "base/2d.h"
#include "core/xresource.h"

namespace xorg {

class Drawable;

class GraphicsContext final : public XResource {
public:
    explicit GraphicsContext(u32 id);

    ~GraphicsContext() override;

    void UpdateValues(u32 mask, const u32* values);

    void SetClipRectangles(s16 x, s16 y, const xRectangle* rectangle, size_t count);

    void SetAttribute(u32 mask, u32 value);

    u32 GetAttribute(u32 mask) const;

    u32 GetFunction() const { return GetAttribute(GCFunction); }

    u32 GetForeground() const { return GetAttribute(GCForeground); }

    u32 GetBackground() const { return GetAttribute(GCBackground); }

    u32 GetLineWidth() const { return GetAttribute(GCLineWidth); }

    const std::vector<Rect>& GetClipRectangles() const { return clip_rectangles; }

private:
    std::array<u32, 23> attributes{};
    std::vector<Rect> clip_rectangles{};
};

}  // namespace xorg