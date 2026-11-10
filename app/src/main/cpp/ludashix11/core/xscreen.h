#pragma once

#include <memory>
#include <mutex>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <jni.h>
#include "base/types.h"

namespace xorg {

class XServer;
class WindowManager;
class Drawable;

namespace ScreenDirtyFlags {
constexpr u32 kWindow = 1 << 0;
constexpr u32 kAll = kWindow;
};  // namespace ScreenDirtyFlags

class XScreen {
public:
    static std::unique_ptr<XScreen> Create(XServer& server);

    explicit XScreen(XServer& server);

    virtual ~XScreen();

    void Dirty(u32 flags) { dirty_flags |= flags; }

    std::unique_lock<std::mutex> LockRenderer() { return std::unique_lock(render_mutex); }

    std::unique_lock<std::mutex> LockUI() { return std::unique_lock(ui_mutex); }

    virtual sp<Drawable> CreateDrawable(u16 width, u16 height, u8 depth) = 0;

    virtual sp<Drawable> CreateDrawable(u16 width, u16 height, AHardwareBuffer* buffer) = 0;

    virtual void UpdateSurface(ANativeWindow* window) = 0;

    virtual void UpdateCursor(Drawable* cursor, s16 x, s16 y) = 0;

    virtual void PostInvalidate() = 0;

    virtual void WaitIdle() = 0;

    virtual void SetUpScalerEnabled(bool enabled) = 0;

    virtual void SetPostProcessingEnabled(bool enabled) = 0;

    void PostInvalidate(u32 flags) {
        Dirty(flags);
        PostInvalidate();
    }

protected:
    std::atomic<u32> dirty_flags{ScreenDirtyFlags::kAll};
    XServer& server;
    std::mutex render_mutex;
    std::mutex ui_mutex;
};

}  // namespace xorg