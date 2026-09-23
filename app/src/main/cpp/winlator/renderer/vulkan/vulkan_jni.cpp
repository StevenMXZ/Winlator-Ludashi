#include <jni.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <string>
#include "../../../adrenotools/include/adrenotools/driver.h"
#include "VulkanRendererContext.h"

static void* openAdrenotoolsDriver(const char* driverPath, const char* libraryName,
                                   const char* nativeLibDir) {
    if (!driverPath || !libraryName || !nativeLibDir) return nullptr;
    if (access(driverPath, F_OK) != 0) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer",
            "openAdrenotoolsDriver: driverPath not accessible: %s", driverPath);
        return nullptr;
    }
    char tmpdir[512];
    snprintf(tmpdir, sizeof(tmpdir), "%stemp", driverPath);
    mkdir(tmpdir, S_IRWXU | S_IRWXG);
    __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Renderer",
        "openAdrenotoolsDriver: driverPath=%s lib=%s nativeLibDir=%s tmp=%s",
        driverPath, libraryName, nativeLibDir, tmpdir);
    setenv("ADRENOTOOLS_DRIVER_PATH", driverPath, 1);
    setenv("ADRENOTOOLS_DRIVER_NAME", libraryName, 1);
    setenv("ADRENOTOOLS_HOOKS_PATH", nativeLibDir, 1);
    const char* redirectDir = getenv("ADRENOTOOLS_REDIRECT_DIR");
    int featureFlags = ADRENOTOOLS_DRIVER_CUSTOM;
    if (redirectDir && redirectDir[0] != '\0') {
        featureFlags |= ADRENOTOOLS_DRIVER_FILE_REDIRECT;
    } else {
        unsetenv("ADRENOTOOLS_DRIVER_FILE_REDIRECT");
    }
    void* handle = adrenotools_open_libvulkan(
        RTLD_LOCAL | RTLD_NOW,
        featureFlags,
        tmpdir,
        nativeLibDir,
        driverPath,
        libraryName,
        (redirectDir && redirectDir[0] != '\0') ? redirectDir : nullptr,
        nullptr);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer",
            "openAdrenotoolsDriver: adrenotools_open_libvulkan failed");
    } else {
        __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Renderer",
            "openAdrenotoolsDriver: SUCCESS handle=%p", handle);
    }
    return handle;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeInit(
    JNIEnv* env, jobject ,
    jobject surface, jint w, jint h,
    jstring jDriverPath, jstring jLibraryName, jstring jNativeLibDir,
    jboolean validationEnabled)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return 0;

    if (validationEnabled == JNI_TRUE) {
        const char* nativeLibDir = jNativeLibDir
            ? env->GetStringUTFChars(jNativeLibDir, nullptr) : nullptr;
        if (nativeLibDir) {
            std::string layerPath = std::string(nativeLibDir) + "/libVkLayer_khronos_validation.so";
            const bool layerPresent = access(layerPath.c_str(), R_OK) == 0;
            __android_log_print(layerPresent
                    ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "Winlator_VulkanValidation",
                "requested layer=%s present=%d", layerPath.c_str(),
                layerPresent ? 1 : 0);
            env->ReleaseStringUTFChars(jNativeLibDir, nativeLibDir);
        }
    }

    void* adrenotoolsHandle = nullptr;
    if (jDriverPath && jLibraryName && jNativeLibDir) {
        const char* dp  = env->GetStringUTFChars(jDriverPath,   nullptr);
        const char* lib = env->GetStringUTFChars(jLibraryName,  nullptr);
        const char* nld = env->GetStringUTFChars(jNativeLibDir, nullptr);
        adrenotoolsHandle = openAdrenotoolsDriver(dp, lib, nld);
        env->ReleaseStringUTFChars(jDriverPath,   dp);
        env->ReleaseStringUTFChars(jLibraryName,  lib);
        env->ReleaseStringUTFChars(jNativeLibDir, nld);
    }

    try {
        return reinterpret_cast<jlong>(
            new VulkanRendererContext(win, w, h, adrenotoolsHandle,
                                      validationEnabled == JNI_TRUE));
    } catch (...) {
        ANativeWindow_release(win);
        if (adrenotoolsHandle) dlclose(adrenotoolsHandle);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeResize(
    JNIEnv*, jobject, jlong h, jint w, jint ht)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(h);
    if (r) r->onSurfaceResized(w, ht);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeDestroy(
    JNIEnv*, jobject, jlong h)
{
    delete reinterpret_cast<VulkanRendererContext*>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUpdateWindowContent(
    JNIEnv* env, jobject, jlong handle, jlong id, jobject buf,
    jshort w, jshort h, jshort stride, jint x, jint y)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !buf) return;
    void* px = env->GetDirectBufferAddress(buf);
    if (px && env->GetDirectBufferCapacity(buf) >= (jlong)w * h * 4)
        r->updateWindowContent(id, px, w, h, stride, x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUpdateWindowContentAHB(
    JNIEnv*, jobject, jlong handle, jlong id, jlong ahbPtr,
    jshort w, jshort h, jint x, jint y)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r && ahbPtr)
        r->updateWindowContentAHB(id, reinterpret_cast<AHardwareBuffer*>(ahbPtr), w, h, x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetTransformAndScissor(
    JNIEnv*, jobject, jlong handle,
    jfloat ox, jfloat oy, jfloat sx, jfloat sy,
    jboolean hasScissor, jint scX, jint scY, jint scW, jint scH)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setTransformAndScissor(ox, oy, sx, sy, (bool)hasScissor, scX, scY, scW, scH);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetPointerPos(
    JNIEnv*, jobject, jlong handle, jshort x, jshort y)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->updatePointerPosition(x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetCursorVisible(
    JNIEnv*, jobject, jlong handle, jboolean v)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setCursorVisible(v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUpdateCursorImage(
    JNIEnv* env, jobject, jlong handle, jobject buf,
    jshort w, jshort h, jshort stride, jshort hotX, jshort hotY)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !buf) return;
    void* px = env->GetDirectBufferAddress(buf);
    jlong required = (jlong)std::max((int)stride, (int)w) * h * 4;
    if (px && env->GetDirectBufferCapacity(buf) >= required)
        r->updateCursorImage(px, w, h, stride, hotX, hotY);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetRenderList(
    JNIEnv* env, jobject, jlong handle,
    jlongArray jids, jintArray jxs, jintArray jys, jint count)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || count <= 0) return;

    jlong* ids = (jlong*)env->GetPrimitiveArrayCritical(jids, nullptr);
    jint*  xs  = (jint*) env->GetPrimitiveArrayCritical(jxs,  nullptr);
    jint*  ys  = (jint*) env->GetPrimitiveArrayCritical(jys,  nullptr);
    r->setRenderList(reinterpret_cast<const int64_t*>(ids), xs, ys, count);
    env->ReleasePrimitiveArrayCritical(jys,  ys,  JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jxs,  xs,  JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jids, ids, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeRemoveWindow(
    JNIEnv*, jobject, jlong handle, jlong id)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->removeWindow(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeInitRootWindow(
    JNIEnv*, jobject, jlong handle, jlong rootId, jlong contentId, jint width, jint height)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->initRootWindow(rootId, contentId, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeCreateWindow(
    JNIEnv*, jobject, jlong handle, jlong id, jlong parentId, jlong contentId,
    jint x, jint y, jint width, jint height)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->createWindowNode(id, parentId, contentId, x, y, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeDestroyWindow(
    JNIEnv*, jobject, jlong handle, jlong id)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->destroyWindowNode(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeMapWindow(
    JNIEnv*, jobject, jlong handle, jlong id)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->mapWindowNode(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUnmapWindow(
    JNIEnv*, jobject, jlong handle, jlong id)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->unmapWindowNode(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeReparentWindow(
    JNIEnv*, jobject, jlong handle, jlong id, jlong newParentId)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->reparentWindowNode(id, newParentId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeUpdateWindowGeometry(
    JNIEnv*, jobject, jlong handle, jlong id, jlong contentId,
    jint x, jint y, jint width, jint height)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->updateWindowGeometryNode(id, contentId, x, y, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSyncChildOrder(
    JNIEnv* env, jobject, jlong handle, jlong parentId, jlongArray jorderedIds, jint count)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || count <= 0) return;
    jlong* ids = (jlong*)env->GetPrimitiveArrayCritical(jorderedIds, nullptr);
    r->syncChildOrder(parentId, reinterpret_cast<const int64_t*>(ids), count);
    env->ReleasePrimitiveArrayCritical(jorderedIds, ids, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetWindowViewable(
    JNIEnv*, jobject, jlong handle, jlong id, jboolean viewable)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setWindowViewable(id, viewable);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetVerboseLog(
    JNIEnv*, jobject, jlong handle, jboolean v)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setVerboseLog((bool)v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeDumpRendererInfo(
    JNIEnv*, jobject, jlong handle)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->dumpRendererInfo();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetFilterMode(
    JNIEnv*, jobject, jlong handle, jint mode)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setFilterMode((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetStretchMode(
    JNIEnv*, jobject, jlong handle, jint mode)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setStretchMode((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetPostFXMode(
    JNIEnv*, jobject, jlong handle, jint mode)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setPostFXMode((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetSharpness(
    JNIEnv*, jobject, jlong handle, jfloat s)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setSharpness((float)s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetSwapRB(
    JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setSwapRB(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeSetPresentMode(
    JNIEnv*, jobject, jlong handle, jint mode)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setPresentMode((VkPresentModeKHR)mode);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeGetSupportedPresentModes(
    JNIEnv* env, jobject, jlong handle)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return env->NewIntArray(0);
    auto modes = r->getSupportedPresentModes();
    jintArray arr = env->NewIntArray((jsize)modes.size());
    if (!modes.empty())
        env->SetIntArrayRegion(arr, 0, (jsize)modes.size(), modes.data());
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeGetSwapchainSize(
    JNIEnv* env, jobject, jlong handle)
{
    jintArray arr = env->NewIntArray(2);
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return arr;
    VkExtent2D ext = r->getSwapchainExtent();
    jint vals[2] = { (jint)ext.width, (jint)ext.height };
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeDetachSurface(
    JNIEnv*, jobject, jlong handle)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->detachSurface();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_VulkanXServerView_nativeReattachSurface(
    JNIEnv* env, jobject, jlong handle, jobject surface)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !surface) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return JNI_FALSE;
    bool ok = r->reattachSurface(win);
    return (jboolean)ok;
}
