#pragma once
#include "core/drawable.h"

namespace xorg {
class NativeDrawable final : public Drawable {
public:
    NativeDrawable(u16 w, u16 h, u8 depth, AHardwareBuffer* buffer = nullptr);
    ~NativeDrawable() override;
    sp<Drawable> Clone() override;
    void ReadPixels(u8*, s16, s16, u16, u16, u16) override;
    void WritePixels(const u8*, s16, s16, s16, s16, u16, u16, u16) override;
    void WriteBinaryPixels(const u8*, u16, u16, u32, u32) override;
    void FillRect(s16, s16, u16, u16, u32) override;
    void DrawLines(const Point*, u32, u32, u32) override;
    void DrawPolySegments(const PolySegment*, u32, u32, u32) override;
    void* Map() override;
    u32 GetStride() override { return stride; }
    AHardwareBuffer* HardwareBuffer() const { return buffer; }
    sp<const std::vector<u8>> Pixels() const { return pixels; }
    u64 Revision() const { return revision; }
    u64 ID() const { return id; }
private:
    void Writable();
    void Line(Point a, Point b, u32 color, u32 width);
    sp<std::vector<u8>> pixels;
    u32 stride{};
    u64 revision{1}, id{};
    void* mapped{};
    bool locked{};
};
}
