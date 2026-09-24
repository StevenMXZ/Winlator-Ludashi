#include "egl.hpp"
#include "effect_composer.hpp"
#include "displayx.hpp"
#include <cstdint>

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define LOG_TAG "Renderer"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

JNICache cache;
JNIXServer xserver;
WindowManager windowManager;
CursorManager cursorManager;
EGLRenderer renderer;
EffectComposer effectComposer;
DisplayX displayX;

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    jint result = vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    
    cache.init(vm, env);
    
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeInit(JNIEnv *env, jobject thiz, jobject context, jobject xServer) {
    jobject windowManagerObj = env->GetObjectField(xServer, cache.windowManager);
    jobject inputDeviceManagerObj = env->GetObjectField(xServer, cache.inputDeviceManager);
    jobject rootWindowObj = env->GetObjectField(windowManagerObj, cache.rootWindow);
    
    jstring displayDriver = (jstring)env->CallObjectMethod(xServer, cache.getDisplayDriver);
    const char *p = env->GetStringUTFChars(displayDriver, nullptr);
    std::string dispDriver(p);
    env->ReleaseStringUTFChars(displayDriver, p);
    
    auto rootWindow = std::make_unique<struct Window>();
    
    rootWindow->id = env->GetIntField(rootWindowObj, cache.windowID);
    rootWindow->width = env->CallShortMethod(rootWindowObj, cache.windowGetWidth);
    rootWindow->height = env->CallShortMethod(rootWindowObj, cache.windowGetHeight);
    rootWindow->x = env->CallShortMethod(rootWindowObj, cache.windowGetX);
    rootWindow->y = env->CallShortMethod(rootWindowObj, cache.windowGetY);
    rootWindow->z_order = INT32_MIN;
    rootWindow->className = "";
    
    auto drawable = std::make_unique<struct Drawable>();
    jobject drawableObj = env->CallObjectMethod(rootWindowObj, cache.windowGetContent);
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->glTexture = nullptr;
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->ahb = (AHardwareBuffer *)env->GetLongField(drawableObj, cache.drawableAHB);
    drawable->stride = env->GetShortField(drawableObj, cache.drawableStride);
    drawable->format = env->GetIntField(drawableObj, cache.drawableFormat);
    drawable->data = nullptr;
    drawable->isDirectContent = false;
    drawable->isDisplayX = false;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);
    rootWindow->drawable = std::move(drawable);
    
    env->DeleteLocalRef(drawableObj);
    
    rootWindow->cursor = nullptr;
    rootWindow->parent = nullptr;
    rootWindow->mapped = true;
    rootWindow->control = nullptr;
    rootWindow->enabled = true;
    rootWindow->inputOutput = true;
    rootWindow->externalContent = nullptr;
    rootWindow->surface = nullptr;
    
    jobject attributes = env->GetObjectField(rootWindowObj, cache.windowAttributes);
    rootWindow->attributes = env->NewGlobalRef(attributes);
    rootWindow->windowObj = env->NewGlobalRef(rootWindowObj);
    
    env->DeleteLocalRef(rootWindowObj);
    env->DeleteLocalRef(attributes);
    
    windowManager.setRootWindow(rootWindow.get());
    windowManager.addWindow(rootWindow->id, std::move(rootWindow));
    
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssets = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManagerObject = env->CallObjectMethod(context, getAssets);
    AAssetManager *assetManager = AAssetManager_fromJava(env, assetManagerObject);
    
    AAsset *cursorAsset = AAssetManager_open(assetManager, "cursor.png", AASSET_MODE_BUFFER);
    off_t len = AAsset_getLength(cursorAsset);
    const unsigned char *data = static_cast<const unsigned char *>(AAsset_getBuffer(cursorAsset));
    
    int w, h, c, ret;
    unsigned char *cursorData = stbi_load_from_memory(data, len, &w, &h, &c, 4);
    
    AAsset_close(cursorAsset);
    
    auto cursorDrawable = std::make_unique<struct Drawable>();
    cursorDrawable->id = -1;
    cursorDrawable->glTexture = nullptr;
    cursorDrawable->isDirectContent = false;
    cursorDrawable->format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    cursorDrawable->width = w;
    cursorDrawable->height = h;
    cursorDrawable->data = nullptr;
    cursorDrawable->isDirectContent = false;
    cursorDrawable->isDisplayX = false;
    cursorDrawable->drawableObj = nullptr;
    
    AHardwareBuffer_Desc desc{};
    desc.width = w;
    desc.height = h;
    desc.format = cursorDrawable->format;
    desc.layers = 1;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                 
    ret = AHardwareBuffer_allocate(&desc, &cursorDrawable->ahb);
    if (ret != 0)
        return; 
        
    AHardwareBuffer_Desc outDesc{};
    AHardwareBuffer_describe(cursorDrawable->ahb, &outDesc);
    cursorDrawable->stride = outDesc.stride;
        
    uint8_t *dst, *src;
        
    ret = AHardwareBuffer_lock(cursorDrawable->ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, reinterpret_cast<void **>(&dst));
    if (ret != 0)    
        return;
        
    src = reinterpret_cast<uint8_t *>(cursorData);
    
    for (int y = 0; y < h; ++y) {
        memcpy(dst + y * cursorDrawable->stride * 4, src + y * cursorDrawable->width * 4, cursorDrawable->width * 4);
    }
    
    AHardwareBuffer_unlock(cursorDrawable->ahb, nullptr);
    
    auto rootCursor = std::make_unique<struct Cursor>();
    rootCursor->id = cursorDrawable->id;
    rootCursor->image = std::move(cursorDrawable);
    rootCursor->hotspotX = 0;
    rootCursor->hotspotY = 0;
    rootCursor->visible = true;
    rootCursor->cursorObj = nullptr;
    
    cursorManager.setRootCursor(std::move(rootCursor));
    
    xserver.windowManager = env->NewGlobalRef(windowManagerObj);
    xserver.inputDeviceManager = env->NewGlobalRef(inputDeviceManagerObj);
    xserver.displayDriver = dispDriver;
    xserver.refreshRate = env->CallFloatMethod(context, cache.getRefreshRate);
    xserver.xserver = env->NewGlobalRef(xServer);
    xserver.xserverDisplayActivity = env->NewGlobalRef(context);
    xserver.isShowFPS.store(env->CallBooleanMethod(context, cache.isShowFPS), std::memory_order_relaxed);
    
    env->DeleteLocalRef(windowManagerObj);
    env->DeleteLocalRef(inputDeviceManagerObj);
    
    effectComposer.init();
    if (xserver.isDisplayX() && windowManager.getRootWindow()->drawable->format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM)
        effectComposer.setColorSwapEnabled(true);
    
    renderer.windowManager = &windowManager;
    renderer.cursorManager = &cursorManager;
    renderer.cache = &cache;
    renderer.xServer = &xserver;
    
    displayX.windowManager = &windowManager;
    displayX.cursorManager = &cursorManager;
    displayX.cache = &cache;
    displayX.xServer = &xserver;
    displayX.effectComposer = &effectComposer;
    
    if (xserver.isDisplayX()) {
        displayX.setPerformanceMode(env->GetBooleanField(context, cache.performanceMode));
        displayX.setPresentRR(env->GetBooleanField(context, cache.presentRR));
        displayX.setBackPressure(env->GetBooleanField(context, cache.backPressure));
        displayX.setPrecisePresentation(env->GetBooleanField(context, cache.precisePresentation));
    } else {
        renderer.setTextureFilter(env->GetIntField(context, cache.textureFilter));
    }
    
    if (xserver.isDisplayX())
        displayX.start();
    else    
        renderer.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateWindow(JNIEnv *env, jobject thiz, jobject windowObj, jint parentId) {
    auto window = std::make_unique<struct Window>();
    window->id = env->GetIntField(windowObj, cache.windowID);
    window->width = env->CallShortMethod(windowObj, cache.windowGetWidth);
    window->height = env->CallShortMethod(windowObj, cache.windowGetHeight);
    window->x = env->CallShortMethod(windowObj, cache.windowGetX);
    window->y = env->CallShortMethod(windowObj, cache.windowGetY);
    window->z_order = 100;
    window->className = "";
    
    bool isInputOutput = env->CallBooleanMethod(windowObj, cache.windowIsInputOutput);
    window->inputOutput = isInputOutput;
    window->drawable = nullptr;
    
    if (isInputOutput) {
        auto drawable = std::make_unique<struct Drawable>();
        jobject drawableObj = env->CallObjectMethod(windowObj, cache.windowGetContent);
        drawable->id = env->GetIntField(drawableObj, cache.drawableID);
        drawable->glTexture = nullptr;
        drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
        drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
        drawable->data = nullptr;
        drawable->ahb = (AHardwareBuffer *)env->GetLongField(drawableObj, cache.drawableAHB);
        drawable->stride = env->GetShortField(drawableObj, cache.drawableStride);
        drawable->format = env->GetIntField(drawableObj, cache.drawableFormat);
        drawable->isDirectContent = false;
        drawable->isDisplayX = false;
        drawable->drawableObj = env->NewGlobalRef(drawableObj);
        window->drawable = std::move(drawable);
        env->DeleteLocalRef(drawableObj);
    }
    
    window->cursor = nullptr;
    window->mapped = false;
    window->parent = nullptr;
    window->compositeRedirected = false;
    window->control = nullptr;
    window->externalContent = nullptr;
    window->surface = nullptr;
    window->enabled = true;
    
    jobject attributes = env->GetObjectField(windowObj, cache.windowAttributes);
    window->attributes = env->NewGlobalRef(attributes);
    window->windowObj = env->NewGlobalRef(windowObj);
    
    env->DeleteLocalRef(attributes);
    
    if (parentId > -1) {
        auto parent = windowManager.getWindow(parentId);
        window->parent = parent;
        parent->children.push_back(window.get());
    }
    
    if (xserver.isDisplayX()) {
        displayX.queueEvent([ptr = window.get()] { displayX.createWindowControl(ptr); });
    }
    
    windowManager.addWindow(window->id, std::move(window));
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeMapWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;
    
    window->mapped = true;
    
    if (xserver.isDisplayX()) {
        displayX.queueEvent([window] { displayX.mapWindow(window); });
    }    
    else { 
        renderer.queueEvent([]{ renderer.updateScene(); });
        renderer.requestRenderer();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUnmapWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;
    
    window->mapped = false;
    
    if (xserver.isDisplayX()) {
        displayX.queueEvent([window] { displayX.unmapWindow(window); });
    } else {   
        renderer.queueEvent([]{ renderer.updateScene(); });
        renderer.requestRenderer();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeDestroyWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;
    
    if (xserver.isDisplayX()) {
        displayX.queueEvent([window] { 
            if (window->inputOutput && window->drawable->composerTexture != nullptr)
                effectComposer.destroyComposerTexture(window->drawable.get());
                
            displayX.destroyWindowControl(window);
            windowManager.deleteWindow(window); 
        });
    }    
    else {
        renderer.queueEvent([window] { 
            if (window->inputOutput && window->drawable->glTexture != nullptr) 
                renderer.destroyTexture(window->drawable->glTexture.get());
                
            windowManager.deleteWindow(window);
            renderer.updateScene(); 
        });
    }    
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateCursor(JNIEnv *env, jobject thiz, jobject cursorObj) {
    auto drawable = std::make_unique<struct Drawable>();
    jobject drawableObj = env->GetObjectField(cursorObj, cache.cursorImage);
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->data = nullptr;
    drawable->ahb = (AHardwareBuffer *)env->GetLongField(drawableObj, cache.drawableAHB);
    drawable->stride = env->GetShortField(drawableObj, cache.drawableStride);
    drawable->format = env->GetIntField(drawableObj, cache.drawableFormat);
    drawable->isDirectContent = false;
    drawable->isDisplayX = false;
    drawable->glTexture = nullptr;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);
    
    env->DeleteLocalRef(drawableObj);
    
    auto cursor = std::make_unique<struct Cursor>();
    cursor->id = env->GetIntField(cursorObj, cache.cursorID);
    cursor->image = std::move(drawable);
    cursor->hotspotX = env->GetIntField(cursorObj, cache.cursorHotspotX);
    cursor->hotspotY = env->GetIntField(cursorObj, cache.cursorHotspotY);
    cursor->visible = env->CallBooleanMethod(cursorObj, cache.cursorIsVisible);
    cursor->cursorObj = env->NewGlobalRef(cursorObj);
   
    cursorManager.addCursor(cursor->id, std::move(cursor));
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeFreeCursor(JNIEnv *env, jobject thiz, jint id) {
    auto cursor = cursorManager.getCursor(id);
    if (!cursor) return;
    
    if (!xserver.isDisplayX()) {
        renderer.queueEvent([cursor] { 
            JNIEnv *env = cache.getEnv();
            if (cursor->image->glTexture != nullptr)
                renderer.destroyTexture(cursor->image->glTexture.get()); 
                
            jobject pointWindowObj = env->CallObjectMethod(xserver.inputDeviceManager, cache.getPointWindow);
            jint id = env->GetIntField(pointWindowObj, cache.windowID);
            auto pointWindow = windowManager.getWindow(id);
            if (pointWindow && pointWindow->cursor == cursor) pointWindow->cursor = nullptr;     
                
            cursorManager.removeCursor(env, cursor);
        });
    }
    else {
        displayX.queueEvent([cursor] {
            JNIEnv *env = cache.getEnv();
            displayX.updateCursor(nullptr);
            cursorManager.removeCursor(env, cursor);
        });
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeBindCursor(JNIEnv *env, jobject thiz, jint windowId, jint cursorId, jboolean visible) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;
    auto cursor = cursorManager.getCursor(cursorId);
    if (!cursor) return;
      
    cursor->visible = visible;
    
    window->cursor = cursor;
    for (auto &child : window->children)
        child->cursor = cursor;
    
    if (!xserver.isDisplayX())    
        renderer.requestRenderer();
    else
        displayX.queueEvent([window] { displayX.updateCursor(window->cursor); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativePointerMove(JNIEnv *env, jobject thiz, jint posX, jint posY) {
    cursorManager.pointer.posX = posX;
    cursorManager.pointer.posY = posY;
    
    if (!xserver.isDisplayX())
        renderer.requestRenderer();
    else 
        displayX.requestCursorUpdate();
}    


extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeChangeWindowZOrder(JNIEnv *env, jobject thiz, jint stackMode, jint id, jint siblingId) {
    auto window = windowManager.getWindow(id);
    auto sibling = windowManager.getWindow(siblingId);
    
    if (!window) return;
    
    windowManager.changeZOrder(stackMode, window, sibling);
    
    if (!xserver.isDisplayX()) {
        renderer.queueEvent([]{ renderer.updateScene(); });
        renderer.requestRenderer();
    }
    else {
        displayX.queueEvent([window] { displayX.changeZOrder(window);});
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateWindowGeometry(JNIEnv *env, jobject thiz, jint id, jint width, jint height, jint x, jint y, jboolean resized) {
    auto window = windowManager.getWindow(id);
    if (!window) return;
    
    window->width = width;
    window->height = height;
    window->x = x;
    window->y = y;
    
    if (resized && window->inputOutput) {
        env->DeleteGlobalRef(window->drawable->drawableObj);
        jobject drawableObj = env->CallObjectMethod(window->windowObj, cache.windowGetContent);
        window->drawable->drawableObj = env->NewGlobalRef(drawableObj);
        env->DeleteLocalRef(drawableObj);
        window->drawable->data = nullptr;
        window->drawable->width = width;
        window->drawable->height = height;
        window->drawable->ahb = (AHardwareBuffer *)env->GetLongField(window->drawable->drawableObj, cache.drawableAHB);
        window->drawable->stride = env->GetShortField(window->drawable->drawableObj, cache.drawableStride);
        if (window->drawable->glTexture != nullptr) window->drawable->glTexture->sizeChanged = true;
        if (window->drawable->composerTexture != nullptr) window->drawable->composerTexture->sizeChanged = true;
    }
    
    if (xserver.isDisplayX()) {
        displayX.queueEvent([window, resized] { displayX.changeGeometry(window, resized); });
    } else {
        if (resized) {
            renderer.queueEvent([]{ renderer.updateScene(); });
        } else {
            renderer.queueEvent([window]{ renderer.updateWindowPosition(window); });
            renderer.queueEvent([]{ renderer.updateScene(); });
        }
        renderer.requestRenderer();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateWindowContent(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;
    
    window->hasContent = true;
    
    if (xserver.isDisplayX()) {
        displayX.requestWindowUpdate(window);
    }    
    else
        renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeReparentWindow(JNIEnv *env, jobject thiz, jint id, jint parentId, jshort x, jshort y) {
    auto window = windowManager.getWindow(id);
    auto parent = windowManager.getWindow(parentId);
    
    if (!window || !parent) return;
    
    window->x = x;
    window->y = y;
    
    windowManager.reparentWindow(window, parent);
    
    if (xserver.isDisplayX()) {
        displayX.queueEvent([window, parent] {  displayX.reparentWindow(window, parent); });
    }    
    else {
        renderer.queueEvent([window]{ renderer.updateWindowPosition(window); });
        renderer.queueEvent([]{ renderer.updateScene(); });
        renderer.requestRenderer();
    }    
}


extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeToggleFullscreen(JNIEnv *env, jobject thiz) {
    renderer.toggleFullscreen = true;
    
    if (!xserver.isDisplayX())
        renderer.requestRenderer();
    else
        displayX.queueEvent([] { displayX.toggleFullscreen(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetCursorVisible(JNIEnv *env, jobject thiz, jboolean visible) {
    renderer.cursorVisible = visible;
    displayX.cursorVisible = visible;
    
    if (!xserver.isDisplayX())
        renderer.requestRenderer();
    else
        displayX.queueEvent([] { displayX.showCursor(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetShowFPS(JNIEnv *env, jobject thiz, jboolean showFPS) {
    xserver.isShowFPS.store(showFPS, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetScreenOffsetYRelativeToCursor(JNIEnv *env, jobject thiz, jboolean cond) {
    renderer.screenOffsetYRelativeToCursor = cond;
    if (!xserver.isDisplayX())
        renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetMagnifierZoom(JNIEnv *env, jobject thiz, float magnifierZoom) {
    renderer.magnifierZoom = magnifierZoom;
    if (!xserver.isDisplayX())
        renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetUnviewableWMClass(JNIEnv *env, jobject thiz, jstring unviewableWMName) {
    const char *chars = env->GetStringUTFChars(unviewableWMName, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(unviewableWMName, chars);
    windowManager.setUnviewableWMClass(str);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetWindowClassName(JNIEnv *env, jobject thiz, jint id, jstring className) {
    auto window = windowManager.getWindow(id);
    if (!window) return;
    
    const char *chars = env->GetStringUTFChars(className, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(className, chars);
    window->className = str;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCreateSurface(JNIEnv *env, jobject thiz, jobject surface) {
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (xserver.isDisplayX())
        displayX.createSurface(window);
    else      
        renderer.createSurface(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeDestroySurface(JNIEnv *env, jobject thiz) {
    if (xserver.isDisplayX())
        displayX.destroySurface();
    else     
        renderer.destroySurface();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeChangeSurface(JNIEnv *env, jobject thiz, jint width, jint height) {
    if (xserver.isDisplayX())
        displayX.changeSurface(width, height);
    else    
        renderer.changeSurface(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativePause(JNIEnv *env, jobject thiz) {
    if (!xserver.isDisplayX())
        renderer.pause();
    else
        displayX.pause();     
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeResume(JNIEnv *env, jobject thiz) {
    if (!xserver.isDisplayX())
        renderer.resume();
    else
        displayX.resume();       
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeStop(JNIEnv *env, jobject thiz) {
    if (!xserver.isDisplayX())
        renderer.stop();
    else    
        displayX.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeAddDirectContent(JNIEnv *env, jobject thiz, jint windowId, jobject drawableObj) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;
    
    auto drawable = std::make_unique<struct Drawable>();
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->glTexture = nullptr;
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->data = nullptr;
    drawable->format = env->GetIntField(drawableObj, cache.drawableFormat);
    drawable->ahb = (AHardwareBuffer *)env->GetLongField(drawableObj, cache.drawableAHB);
    drawable->stride = env->GetShortField(drawableObj, cache.drawableStride);
    drawable->isDirectContent = true;
    drawable->isDisplayX = false;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);
    
    if (window->parent) window->parent->surface = window;
    window->externalContent = nullptr;
    window->directContents[drawable->id] = std::move(drawable);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeUpdateDirectContent(JNIEnv *env, jclass obj, jint windowId, jint drawableId) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;
    
    auto directContent = window->directContents[drawableId].get();
    if (!directContent) return;
    
    window->externalContent = directContent;
    
    if (xserver.isDisplayX())
        displayX.requestWindowUpdate(window);
    else    
        renderer.requestRenderer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeRemoveDirectContent(JNIEnv *env, jclass obj, jint windowId, jint drawableId) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;
    
    window->directContents.erase(drawableId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeSetCompositeRedirected(JNIEnv *env, jclass obj, jint windowId, jboolean redirected) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;
    
    window->compositeRedirected = redirected;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_XServerView_nativeCompositeRedirect(JNIEnv *env, jclass obj, jint srcDrawableId, jint dstDrawableId, jshort dstX, jshort dstY) {
    auto srcWindow = windowManager.getWindow(srcDrawableId);
    if (!srcWindow) return;
    
    auto dstWindow = windowManager.getWindow(dstDrawableId);
    if (!dstWindow) return;
    
    if (!srcWindow->isAncestorOf(dstWindow)) {
        bool positionChanged = false;
        bool zOrderChanged = false;
        
        auto sibling = srcWindow->getWindowSibling(dstWindow);
        if (!sibling) return;
        
        int posX = dstWindow->x + dstX;
        int posY = dstWindow->y + dstY;
        
        if (sibling->x != posX || sibling->y != posY) {
            sibling->x = posX;
            sibling->y = posY;
            positionChanged = true;
        }
        
        if (sibling->z_order <= dstWindow->z_order) {
            windowManager.changeZOrder(1, sibling, dstWindow);
            zOrderChanged = true;
        }    
        
        if (xserver.isDisplayX()) {
            if (positionChanged) displayX.queueEvent([sibling] { displayX.changeGeometry(sibling, false); });
            if (zOrderChanged) displayX.queueEvent([sibling] { displayX.changeZOrder(sibling); });
        }     
        else {
            if (positionChanged || zOrderChanged)  {
                renderer.queueEvent([sibling]{ renderer.updateWindowPosition(sibling); });
                renderer.queueEvent([sibling]{ renderer.updateScene(); });
            }    
        }
    }
}
