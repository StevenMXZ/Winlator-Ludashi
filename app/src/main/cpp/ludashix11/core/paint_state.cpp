#include "core/paint_state.h"

#include <algorithm>
#include <bit>
#include "native_drawable.h"
#include "core/request_error.h"

namespace ludashi::x11 {
namespace {
constexpr u32 allowed = (1u << (GCLastBit + 1)) - 1;
}

PaintState::PaintState(u32 id, u8 imageDepth)
    : ProtocolObject(ObjectKind::GC, id), depth(imageDepth),
      attributes{GXcopy, 0xffffffff, 0, 1, 0, LineSolid, CapButt, JoinMiter,
                 FillSolid, EvenOddRule, 0, 0, 0, 0, 0, ClipByChildren, 1, 0, 0, 0, 0, 4, ArcPieSlice} {}

void PaintState::UpdateValues(u32 mask, const u32* values) {
    if (mask & ~allowed) throw BadValueError(mask);
    if (mask & GCClipMask) rectangleClip.reset();
    while (mask) {
        const u32 bit = mask & (0u - mask);
        attributes[std::countr_zero(bit)] = *values++;
        mask ^= bit;
    }
}

void PaintState::CopyValues(const PaintState& source, u32 mask) {
    if (mask & ~allowed) throw BadValueError(mask);
    if (depth != source.depth) throw BadMatchError();
    if (mask & GCClipMask) rectangleClip = source.rectangleClip;
    if (mask == allowed) {
        attributes = source.attributes;
        return;
    }
    while (mask) {
        const u32 bit = mask & (0u - mask);
        const auto index = std::countr_zero(bit);
        attributes[index] = source.attributes[index];
        mask ^= bit;
    }
}

void PaintState::SetClipRectangles(s16 x, s16 y, const xRectangle* rectangles, size_t count) {
    auto region = std::make_shared<std::vector<Rect>>();
    region->reserve(count);
    for (size_t index = 0; index < count; ++index) {
        const auto& r = rectangles[index];
        region->push_back({r.x, r.y, s32{r.x} + r.width, s32{r.y} + r.height});
    }
    attributes[std::countr_zero(u32(GCClipXOrigin))] = u32(x);
    attributes[std::countr_zero(u32(GCClipYOrigin))] = u32(y);
    attributes[std::countr_zero(u32(GCClipMask))] = None;
    rectangleClip = std::move(region);
}

void PaintState::PaintRectangle(NativeDrawable& image, const Rect& bounds, u32 color) const {
    const Rect clipped = bounds.Intersection({0, 0, image.GetWidth(), image.GetHeight()});
    if (clipped.Empty()) return;
    auto fill = [&](const Rect& area) {
        if (!area.Empty())
            image.FillRect(area.left, area.top, area.right - area.left, area.bottom - area.top, color);
    };
    if (!rectangleClip) { fill(clipped); return; }
    const s32 x = static_cast<s16>(attributes[std::countr_zero(u32(GCClipXOrigin))]);
    const s32 y = static_cast<s16>(attributes[std::countr_zero(u32(GCClipYOrigin))]);
    for (const auto& r : *rectangleClip)
        fill(clipped.Intersection({r.left + x, r.top + y, r.right + x, r.bottom + y}));
}
} // namespace ludashi::x11
