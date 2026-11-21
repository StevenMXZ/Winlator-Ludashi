#pragma once

#include <array>
#include <bit>
#include <memory>
#include <vector>
#include <X11/X.h>
#include <X11/Xprotostr.h>
#include "base/rectangle.h"
#include "core/protocol_object.h"

namespace ludashi::x11 {
class NativeDrawable;

class PaintState final : public ProtocolObject {
public:
    PaintState(u32 id, u8 depth);
    void UpdateValues(u32 mask, const u32* values);
    void CopyValues(const PaintState& source, u32 mask);
    void SetClipRectangles(s16 x, s16 y, const xRectangle* rectangles, size_t count);
    bool ReportsExposures() const { return attributes[std::countr_zero(u32(GCGraphicsExposures))] != 0; }
    u8 GetDepth() const { return depth; }
    u32 GetFunction() const { return attributes[0]; }
    u32 GetForeground() const { return attributes[2]; }
    u32 GetBackground() const { return attributes[3]; }
    u32 GetLineWidth() const { return attributes[4]; }
    // Solid color rectangle path. Pixmap clip masks, non-copy raster operations,
    // plane masks and dashed/tiled rendering still require separate support.
    void PaintRectangle(NativeDrawable& image, const Rect& bounds, u32 color) const;

private:
    const u8 depth;
    std::array<u32, 23> attributes;
    // Null means no rectangular clip; an empty region clips every pixel.
    // Immutable regions allow CopyGC to share storage without aliasing later edits.
    std::shared_ptr<const std::vector<Rect>> rectangleClip;
};
} // namespace ludashi::x11
