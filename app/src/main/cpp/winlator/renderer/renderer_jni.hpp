#pragma once

#include <jni.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/surface_control.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#define HAL_PIXEL_FORMAT_BGRA_8888 5

#define LOAD_METHOD_ID(method, env, cls, name, sig) \
    (method) = (env)->GetMethodID((cls), (name), (sig));
    
#define LOAD_FIELD_ID(field, env, cls, name, sig) \
    (field) = (env)->GetFieldID((cls), (name), (sig));
    
class JNIXServer {
    public:
        jobject windowManager;
        jobject inputDeviceManager;
        jobject xserver;
        jobject xserverDisplayActivity;
        std::string displayDriver;
        float refreshRate;
        bool isShowFPS;
        
        JNIXServer() {}
        
        bool isDisplayX() {
            return displayDriver == "displayx";
        }
};

class JNICache {
    public: 
        JavaVM *vm;
        
        jclass xserverClass;
        jmethodID getDisplayDriver;
        jmethodID getRefreshRate;
        jfieldID windowManager;
        jfieldID inputDeviceManager;
        
        jclass inputDeviceManagerClass;
        jmethodID getPointWindow;
        
        jclass windowManagerClass;
        jfieldID rootWindow;
        
        jclass cursorClass;
        jmethodID cursorIsVisible;
        jfieldID cursorID;
        jfieldID cursorHotspotX;
        jfieldID cursorHotspotY;
        jfieldID cursorImage;
        
        jclass windowClass;
        jmethodID windowGetWidth;
        jmethodID windowGetHeight;
        jmethodID windowGetX;
        jmethodID windowGetY;
        jmethodID windowGetClassName;
        jmethodID windowGetContent;
        jmethodID windowIsInputOutput;
        jfieldID windowID;
        jfieldID windowAttributes;
        
        jclass windowAttributesClass;
        jmethodID windowAttributesIsEnabled;
        
        jclass drawableClass;
        jmethodID drawableGetGPUImage;
        jfieldID drawableID;
        jfieldID drawableWidth;
        jfieldID drawableHeight;
        
        jclass gpuImageClass;
        jmethodID gpuImageGetStride;
        jmethodID gpuImageGetAHB;
        jfieldID gpuImageFormat;
        
        jclass xserverDisplayActivityClass;
        jmethodID updateFrameRating;
        jmethodID isShowFPS;
        jfieldID performanceMode;
        jfieldID presentRR;
        jfieldID textureFilter;
        
        JNICache() {}
        
        JNIEnv *getEnv() {
            JNIEnv *env = nullptr;
            jint result = vm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (result == JNI_EDETACHED) {
                vm->AttachCurrentThread(&env, nullptr);
            }
            return env;
        }
        
        void detachEnv(JNIEnv *env) {
            vm->DetachCurrentThread();
            delete env;
        }
        
        void init (JavaVM *vm, JNIEnv *env) {
            this->vm = vm;
            
            jclass xServerClass = env->FindClass("com/winlator/cmod/xserver/XServer");
            jclass windowClass = env->FindClass("com/winlator/cmod/xserver/Window");
            jclass windowAttributesClass = env->FindClass("com/winlator/cmod/xserver/WindowAttributes");
            jclass windowManagerClass = env->FindClass("com/winlator/cmod/xserver/WindowManager");
            jclass inputDeviceManagerClass = env->FindClass("com/winlator/cmod/xserver/InputDeviceManager");
            jclass drawableClass = env->FindClass("com/winlator/cmod/xserver/Drawable");
            jclass cursorClass = env->FindClass("com/winlator/cmod/xserver/Cursor");
            jclass gpuImageClass = env->FindClass("com/winlator/cmod/renderer/GPUImage");
            jclass xserverDisplayActivityClass = env->FindClass("com/winlator/cmod/XServerDisplayActivity");
            
            LOAD_METHOD_ID(getDisplayDriver, env, xServerClass, "getDisplayDriver", "()Ljava/lang/String;");
            
            LOAD_FIELD_ID(inputDeviceManager, env, xServerClass, "inputDeviceManager", "Lcom/winlator/cmod/xserver/InputDeviceManager;");
            LOAD_METHOD_ID(getPointWindow, env, inputDeviceManagerClass, "getPointWindow", "()Lcom/winlator/cmod/xserver/Window;");
            
            LOAD_FIELD_ID(windowManager, env, xServerClass, "windowManager", "Lcom/winlator/cmod/xserver/WindowManager;");
            LOAD_FIELD_ID(rootWindow, env, windowManagerClass, "rootWindow", "Lcom/winlator/cmod/xserver/Window;");
            
            LOAD_METHOD_ID(windowGetHeight, env, windowClass, "getHeight", "()S");
            LOAD_METHOD_ID(windowGetWidth, env, windowClass, "getWidth", "()S");
            LOAD_METHOD_ID(windowGetX, env, windowClass, "getX", "()S");
            LOAD_METHOD_ID(windowGetY, env, windowClass, "getY", "()S");
            LOAD_METHOD_ID(windowGetClassName, env, windowClass, "getClassName", "()Ljava/lang/String;");
            LOAD_METHOD_ID(windowIsInputOutput, env, windowClass, "isInputOutput", "()Z");
            LOAD_METHOD_ID(windowAttributesIsEnabled, env, windowAttributesClass, "isEnabled", "()Z");
            LOAD_METHOD_ID(windowGetContent, env, windowClass, "getContent", "()Lcom/winlator/cmod/xserver/Drawable;");
            LOAD_FIELD_ID(windowID, env, windowClass, "id", "I");
            LOAD_FIELD_ID(windowAttributes, env, windowClass, "attributes", "Lcom/winlator/cmod/xserver/WindowAttributes;");
            
            LOAD_FIELD_ID(drawableID, env, drawableClass, "id", "I");
            LOAD_FIELD_ID(drawableWidth, env, drawableClass, "width", "S");
            LOAD_FIELD_ID(drawableHeight, env, drawableClass, "height", "S");
            LOAD_METHOD_ID(drawableGetGPUImage, env, drawableClass, "getGPUImage", "()Lcom/winlator/cmod/renderer/GPUImage;");
            
            LOAD_METHOD_ID(cursorIsVisible, env, cursorClass, "isVisible", "()Z");
            LOAD_FIELD_ID(cursorID, env, cursorClass, "id", "I");
            LOAD_FIELD_ID(cursorHotspotX, env, cursorClass, "hotSpotX", "I");
            LOAD_FIELD_ID(cursorHotspotY, env, cursorClass, "hotSpotY", "I");
            LOAD_FIELD_ID(cursorImage, env, cursorClass, "cursorImage", "Lcom/winlator/cmod/xserver/Drawable;");
            
            LOAD_METHOD_ID(gpuImageGetStride, env, gpuImageClass, "getStride", "()S");
            LOAD_METHOD_ID(gpuImageGetAHB, env, gpuImageClass, "getAHB", "()J");
            LOAD_FIELD_ID(gpuImageFormat, env, gpuImageClass, "format", "I");
            
            LOAD_METHOD_ID(updateFrameRating, env, xserverDisplayActivityClass, "updateFrameRating", "(I)V");
            LOAD_METHOD_ID(isShowFPS, env, xserverDisplayActivityClass, "isShowFPS", "()Z");
            LOAD_METHOD_ID(getRefreshRate, env, xserverDisplayActivityClass, "getRefreshRate", "()F");
            LOAD_FIELD_ID(performanceMode, env, xserverDisplayActivityClass, "performanceMode", "Z");
            LOAD_FIELD_ID(presentRR, env, xserverDisplayActivityClass, "presentRR", "Z");
            LOAD_FIELD_ID(textureFilter, env, xserverDisplayActivityClass, "textureFilter", "I");
            
            this->xserverClass = (jclass)env->NewGlobalRef(xServerClass);
            this->windowClass = (jclass)env->NewGlobalRef(windowClass);
            this->windowManagerClass = (jclass)env->NewGlobalRef(windowManagerClass);
            this->windowAttributesClass = (jclass)env->NewGlobalRef(windowAttributesClass);
            this->inputDeviceManagerClass = (jclass)env->NewGlobalRef(inputDeviceManagerClass);
            this->drawableClass = (jclass)env->NewGlobalRef(drawableClass);
            this->cursorClass = (jclass)env->NewGlobalRef(cursorClass);
            this->gpuImageClass = (jclass)env->NewGlobalRef(gpuImageClass);
            this->xserverDisplayActivityClass = (jclass)env->NewGlobalRef(xserverDisplayActivityClass);
        }
};

