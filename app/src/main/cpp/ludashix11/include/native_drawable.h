#pragma once
#include <android/hardware_buffer.h>
#include <memory>
#include <vector>
#include <X11/Xmd.h>
#include <X11/Xprotostr.h>
#include "base/native_types.h"

namespace ludashi::x11 {
class NativeDrawable final {
public:
    NativeDrawable(u16 w, u16 h, u8 depth, AHardwareBuffer* buffer = nullptr);
    ~NativeDrawable();
    NativeDrawable(const NativeDrawable&) = delete;
    NativeDrawable& operator=(const NativeDrawable&) = delete;

    u16 GetWidth() const { return width; }
    u16 GetHeight() const { return height; }
    u8 GetDepth() const { return depth; }
    void ReadPixels(u8*, s16, s16, u16, u16, u16);
    void WritePixels(const u8*, s16, s16, s16, s16, u16, u16, u16);
    void WriteBinaryPixels(const u8*, u16, u16, u32, u32);
    void CopyAreaFrom(NativeDrawable& source, s16 sx, s16 sy, s16 dx, s16 dy, u16 w, u16 h);
    void FillRect(s32, s32, u32, u32, u32);
    void DrawLines(const xPoint*, u32, u32, u32);
    void DrawPolySegments(const xSegment*, u32, u32, u32);
    void* Map();
    u32 GetStride() const { return stride; }
    AHardwareBuffer* HardwareBuffer() const { return buffer; }
    sp<const std::vector<u8>> Pixels() const { return pixels; }
    bool HasContent() const { return contentReady || buffer != nullptr; }
    void AwaitContent() { scenePending = true; }
    bool PublishContent() {
        const bool changed = scenePending && !HasContent();
        contentReady = true; scenePending = false;
        return changed;
    }
    u64 Revision() const { return revision; }
    u64 ID() const { return id; }
private:
    void Writable();
    void FillClipped(s32 left, s32 top, s32 right, s32 bottom, u32 color, u8*& destination);
    void Line(xPoint a, xPoint b, u32 color, u32 width, u8*& destination);
    const u16 width, height;
    const u8 depth;
    AHardwareBuffer* buffer{};
    sp<std::vector<u8>> pixels;
    u32 stride{};
    u64 revision{1}, id{};
    void* mapped{};
    bool locked{}, contentReady{}, scenePending{};
};
}

