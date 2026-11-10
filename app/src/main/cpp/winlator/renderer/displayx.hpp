#pragma once

#include <string>
#include <algorithm>
#include <thread>
#include <functional>
#include <queue>
#include <cmath>
#include <dlfcn.h>
#include <unordered_set>
#include <condition_variable>

#include <android/choreographer.h>
#include <android/performance_hint.h>
#include <android/hdr_metadata.h>
#include <android/data_space.h>

#include <sys/eventfd.h>

#include "renderer_jni.hpp"
#include "view_transformation.hpp"
#include "window.hpp"
#include "effect_composer.hpp"
#include "cursor.hpp"
#include "displayx_present_queue.hpp"
#include "displayx_protocol.hpp"

#define VK_COLOR_SPACE_SRGB_NONLINEAR_KHR 0
#define VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT 1000104002
#define VK_COLOR_SPACE_HDR10_ST2084_EXT 1000104008

#define VK_PRESENT_MODE_MAILBOX_KHR 1
#define VK_PRESENT_MODE_FIFO_KHR 2

namespace DisplayXCodes {
    static constexpr int CREATE_SWAPCHAIN = 1;
    static constexpr int DESTROY_SWAPCHAIN = 2;
    static constexpr int PRESENT = 3;
    static constexpr int PRESENT_COMPLETE = 4;
    static constexpr int RESET_STATE = 5;
    static constexpr int HDR_METADATA = 6;
}

class DisplayX {
    private:
        enum class State {
            NONE,
            PAUSE,
            RESUME,
            CREATE_SURFACE,
            DESTROY_SURFACE,
            CHANGE_SURFACE
        };
        
        struct DisplayXLock {
           std::condition_variable cv;
           std::mutex mutex;
           
           std::unique_lock<std::mutex> lock() {
               return std::unique_lock<std::mutex>(mutex);
           }
           
           template<typename Predicate>
           void wait(std::unique_lock<std::mutex>& lock, Predicate pred) {
               cv.wait(lock, pred);
           }
           
           void notify() {
               cv.notify_all();
           }
        };
        
        struct DisplayXSwapchain {
            uint8_t id;
            Window *window;
            uint32_t windowId;
            uint64_t windowGeneration;
            uint32_t imageCount;
            uint32_t presentMode;
            uint32_t format;
            uint32_t colorSpace;
            std::array<float, 12> hdr{};
            bool hasHdr{};
            uint32_t width;
            uint32_t height;
            int fd;
            std::vector<std::unique_ptr<Drawable>> images;
            ~DisplayXSwapchain() {
                for (auto& image : images) if (image && image->ahb) AHardwareBuffer_release(image->ahb);
            }
        };
        
        using PresentRequest = DisplayXPresentRequest;
        using PresentQueue = DisplayXPresentQueue;
        struct OnCompleteContext {
            std::vector<std::unique_ptr<PresentRequest>> requests;
        };
        JNIEnv *env;
        int surfaceWidth;
        int surfaceHeight;
        AChoreographer *choreographer;
        ViewTransformation viewTransformation;
        ANativeWindow *native_window;
        APerformanceHintManager *performanceHintManager = nullptr;
        APerformanceHintSession *performanceHintSession = nullptr;
        ASurfaceTransaction *windowTransaction;
        ASurfaceTransaction *cursorTransaction;

        DisplayXLock windowLock;
        std::queue<std::function<void()>> windowQueue;
        PresentQueue presentRequests;
        std::thread windowThread;
        
        int stopFd{-1};
        int serverFd{-1};
        int epollFd{-1};
        std::unordered_map<uint64_t, std::unique_ptr<DisplayXSwapchain>> clientSwapchains;
        std::thread networkThread;
        
        DisplayXLock cursorLock;
        std::queue<std::function<void()>> cursorQueue;
        std::thread cursorThread;

        struct FrameCallback { DisplayX* self; uint64_t generation; };
        std::atomic<uint64_t> frameGeneration{0};
        State state = State::NONE;
        std::atomic_bool paused{false};
        std::atomic_bool stopped{false};
        std::atomic_bool hasSurface{false};
        std::atomic_bool cursorUpdate{false};
        std::atomic_bool surfaceChanged{false};
        std::atomic_bool perfMode{true};
        std::atomic_bool presentRR{false};
        std::atomic_bool defaultRR{false};
        
        bool requestUpdate = false;
        
        bool fullscreen = false;
        AVsyncId vsyncId = -1;
        
        void windowThreadLoop();
        void networkThreadLoop();
        void cursorThreadLoop();

        static void onFrameCallback64(int64_t frameTimeNanos, void *data);
        static void onVsyncCallback(const AChoreographerFrameCallbackData* callbackData, void* data);
        static void onCompleteCallback(void *context, ASurfaceTransactionStats *stats);
        int64_t getCurrentTimeNanos();
        bool isPerformanceHintAPIAvailable();
        
        void createRootWindowControl();
        void createWindowControlLocked(Window* window);
        void createRootCursorControl();
        void resizeRootWindow();
        void destroyRootWindowControl();
        void destroyRootCursorControl();
        void restoreControlState();
        
    public:
        WindowManager *windowManager;
        CursorManager *cursorManager;
        JNIXServer *xServer;
        JNICache *cache;
        EffectComposer *effectComposer;
        
        bool cursorVisible = false;
        
        void start();
        void createSurface(ANativeWindow *window);
        void changeSurface(int width, int height);
        void destroySurface();
        void stop();
        void pause();
        void resume();
        
        void queueWindowEvent(std::function<void()> func);
        void requestWindowUpdate(Window *window);

        void queueCursorEvent(std::function<void()> func);
        void requestCursorUpdate();
        
        void createWindowControl(Window *window);
        void releaseWindowControls(Window *window);
        void destroyWindow(int id);
        void mapWindow(Window *window);
        void unmapWindow(Window *window);
        void changeGeometry(Window *window, bool resized);
        void changeZOrder(Window *window);
        void reparentWindow(Window *window, Window *parent);
        void updateCursor(Cursor *cursor);
        void showCursor();
        void toggleFullscreen();
        void setPerformanceMode(bool perfMode);
        void setPresentRR(bool presentRR);
        void setPrecisePresentation(bool) {}
};
