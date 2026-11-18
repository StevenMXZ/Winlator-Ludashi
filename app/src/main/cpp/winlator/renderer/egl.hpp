#pragma once

#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <android/log.h>

#include <string>
#include <algorithm>
#include <thread>
#include <functional>
#include <queue>
#include <cmath>
#include <condition_variable>

#include "shader.hpp"
#include "renderer_jni.hpp"
#include "window.hpp"
#include "view_transformation.hpp"
#include "xform.hpp"

class EGLRenderer {
    private:
        struct RenderableWindow {
            int rootX;
            int rootY;
            Window *window;
        };
        
        enum class State {
            NONE,
            PAUSE,
            RESUME,
            CREATE_SURFACE,
            DESTROY_SURFACE,
            CHANGE_SURFACE
        };
        
        enum class TextureFilter {
            Nearest = 0,
            Linear = 1
        };
        
        struct RenderLock {
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
        
        EGLDisplay display;
        JNIEnv *env;
        EGLConfig config;
        EGLSurface surface = EGL_NO_SURFACE;
        EGLContext context = EGL_NO_CONTEXT;
        DrawableShader *drawableShader;
        ANativeWindow *window;
        std::vector<std::unique_ptr<struct RenderableWindow>> renderableWindows;
        std::thread renderingThread;
        ViewTransformation viewTransformation;
        int surfaceWidth;
        int surfaceHeight;
        bool fullscreen = false;
        bool viewportNeedsUpdate = true;
        float tmpXForm1[6] = {1, 0, 0, 1, 0, 0};
        float tmpXForm2[6] = {1, 0, 0, 1, 0, 0};
        TextureFilter textureFilter;
        
        RenderLock renderLock;
        State state = State::NONE;
        std::queue<std::function<void()>> eventQueue;
        
        std::atomic_bool stopped{false};
        std::atomic_bool requestUpdate{false};
        
        void renderingThreadLoop();
        void renderDrawable(Drawable *drawable, int x, int y, bool isWindow);
        EGLBoolean drawFrame();
        void renderWindows();
        void destroyEGLSurface();
        void destroyEGLContext();
        void renderCursor();
        void renderDrawable(GLTexture *texture, int length, float xform[], bool isFromWindow, bool swapColors);
        void updateTextureDrawable(GLTexture *texture, int width, int height, void *data);
        std::unique_ptr<GLTexture> allocateTexture(int width, int height);
        std::unique_ptr<GLTexture> allocateTextureDirect(AHardwareBuffer* hardwareBuffer);
        void reallocateTexture(GLTexture *texture, int width, int height);
        void reallocateTextureDirect(GLTexture *texture, AHardwareBuffer* hardwareBuffer);
        void init();
        void createEGLSurface(ANativeWindow *window);
        void collectRenderableWindows(Window *window, int x, int y);
    
    public:
        bool screenOffsetYRelativeToCursor = false;
        bool toggleFullscreen = false;
        bool magnifierEnabled = true;
        float magnifierZoom = 1.0f;
        bool cursorVisible = true;
        WindowManager *windowManager;
        CursorManager *cursorManager;
        JNICache *cache;
        JNIXServer *xServer;
        
        EGLRenderer() {}
        EGLRenderer(WindowManager *manager, CursorManager *cm, JNICache *c) : windowManager(manager), cursorManager(cm), cache(c) {}
        
        void updateScene();
        void start();
        void stop();
        void pause();
        void resume();
        void updateWindowPosition(Window *window);
        void queueEvent(std::function<void()> func);
        void requestRenderer();
        void destroyTexture(GLTexture *texture);
        void destroySurface();
        void createSurface(ANativeWindow *window);
        void changeSurface(int width, int height);
        void setTextureFilter(int textureFilter);
};