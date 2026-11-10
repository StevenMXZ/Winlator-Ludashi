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

#include "renderer_jni.hpp"
#include "view_transformation.hpp"
#include "window.hpp"
#include "effect_composer.hpp"
#include "cursor.hpp"
#include "displayx_present_queue.hpp"

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
        
        using PresentRequest = DisplayXPresentRequest;
        using PresentQueue = DisplayXPresentQueue;
        
        struct DisplayXSwapchain {
            uint8_t id;
            Window *window;
            uint32_t windowId;
            uint64_t windowGeneration;
            int clientFd;
            uint32_t imageCount;
            uint32_t presentMode;
            uint32_t format;
            uint32_t width;
            uint32_t height;
            std::vector<std::unique_ptr<Drawable>> images;
            ~DisplayXSwapchain() {
                for (auto& image : images) if (image && image->ahb) AHardwareBuffer_release(image->ahb);
            }
        };
        
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
        std::mutex performanceHintLock;
        
        DisplayXLock eventLock;
        DisplayXLock presentLock;
        
        ASurfaceTransaction *windowTransaction;
        ASurfaceTransaction *cursorTransaction;
        PresentQueue presentRequests;
        std::queue<std::function<void()>> eventQueue;
        
        std::thread eventThread;
        std::thread networkThread;
        std::thread presentThread;
        
        State state = State::NONE;
        std::atomic_bool paused{false};
        std::atomic_bool stopped{false};
        std::atomic_bool hasSurface{false};
        std::atomic_bool cursorUpdate{false};
        std::atomic_bool surfaceChanged{false};
        std::atomic_bool perfMode{true};
        std::atomic_bool presentRR{true};
        std::atomic_bool precisePresentation{false};
        
        bool requestUpdate = false;
        
        bool fullscreen = false;
        int eventsPending = 0;
        int64_t previousReportedWorkTime = 0;
        AVsyncId vsyncId = -1;
        
        void eventThreadLoop();
        void networkThreadLoop();
        void presentThreadLoop();
        static void onFrameCallback64(int64_t frameTimeNanos, void *data);
        static void onVsyncCallback(const AChoreographerFrameCallbackData* callbackData, void* data);
        static void onCommitCallback(void *context, ASurfaceTransactionStats *stats);
        static void onCompleteCallback(void *context, ASurfaceTransactionStats *stats);
        int64_t getCurrentTimeNanos();
        bool isPerformanceHintAPIAvailable();
        
        void createRootWindowControl();
        void createRootCursorControl();
        void resizeRootWindow();
        void destroyRootWindowControl();
        void destroyRootCursorControl();
        void restoreControlState();
        void releaseWindowControls(Window* window);
        
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
        
        void queueEvent(std::function<void()> func);
        void requestWindowUpdate(Window *window);
        void requestCursorUpdate();
        void updateCursorPosition();
        
        void createWindowControl(Window *window);
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
        void setPrecisePresentation(bool precisePresentation);
};
