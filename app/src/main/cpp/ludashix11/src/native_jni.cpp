#include <jni.h>
#include <android/native_window_jni.h>
#include "core/xserver.h"
#include "core/keyboard.h"
#include "core/mouse.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/atom.h"
#include "native_screen.h"
#include <map>

namespace {
std::mutex sessions_mutex;
std::map<jlong, sp<xorg::XServer>> sessions;
jlong next_session = 1;
sp<xorg::XServer> session(jlong handle) {
    std::lock_guard lock(sessions_mutex);
    auto i = sessions.find(handle); return i == sessions.end() ? nullptr : i->second;
}
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
struct JavaString {
    JNIEnv* env; jstring value; const char* text;
    JavaString(JNIEnv* e, jstring s) : env(e), value(s), text(e->GetStringUTFChars(s, nullptr)) {}
    ~JavaString() { if (text) env->ReleaseStringUTFChars(value, text); }
};
}
#define JNI_NAME(name) Java_com_winlator_cmod_widget_NativeX11View_##name
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(nativeCreate)(JNIEnv* env, jclass, jint width, jint height, jboolean validation,
        jstring driverPath, jstring libraryName, jstring nativeLibDir, jint presentMode, jboolean swapRB, jint backend, jint flags) {
    try {
        if (width < 1 || height < 1 || width > 16384 || height > 16384)
            throw std::runtime_error("Invalid native X11 resolution");
        JavaString path(env, driverPath), library(env, libraryName), nativeLib(env, nativeLibDir);
        if (!path.text || !library.text || !nativeLib.text) return 0;
        xorg::NativeRendererConfig config;
        config.driverPath = path.text; config.libraryName = library.text; config.nativeLibDir = nativeLib.text;
        config.swapRB = swapRB == JNI_TRUE; config.presentMode = presentMode == 1 ? 1 : 2;
        config.backend = backend == 2 ? xorg::NativeRendererConfig::Backend::DisplayX
                : backend == 1 ? xorg::NativeRendererConfig::Backend::EGL : xorg::NativeRendererConfig::Backend::Vulkan;
        config.trueDisplayX = flags & 1; config.performanceMode = flags & 2;
        config.presentAtRefreshRate = flags & 4; config.precisePresentation = flags & 8;
        auto server = std::make_shared<xorg::XServer>(nullptr, xorg::ScreenInfo(width, height, 4),
                validation == JNI_TRUE, std::move(config));
        std::lock_guard lock(sessions_mutex); jlong handle = next_session++;
        sessions.emplace(handle, std::move(server)); return handle;
    } catch (const std::exception& e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeStart)(JNIEnv* env, jclass, jlong handle, jstring path, jstring shm) {
    auto server = session(handle); if (!server) return;
    JavaString socketPath(env, path), shmPath(env, shm);
    if (!socketPath.text || !shmPath.text) return;
    try { auto lock = server->Lock(); server->Start(socketPath.text, shmPath.text); }
    catch (const std::exception& e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeDestroy)(JNIEnv*, jclass, jlong handle) {
    sp<xorg::XServer> server;
    { std::lock_guard lock(sessions_mutex); auto i = sessions.find(handle);
      if (i != sessions.end()) { server = std::move(i->second); sessions.erase(i); } }
    // Release outside registry lock: shutdown joins native worker threads.
    server.reset();
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeSurface)(JNIEnv* env, jclass, jlong handle, jobject surface) {
    auto server = session(handle); if (!server) return;
    auto* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    server->UpdateSurface(window);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeFrameTimeline)(JNIEnv*, jclass, jlong handle, jlong id) {
    auto server = session(handle);
    if (server) static_cast<xorg::NativeScreen&>(server->GetScreen()).SetFrameTimeline(id);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeInput)(JNIEnv* env, jclass, jlong handle, jint kind, jint a, jint b) {
    auto server = session(handle); if (!server) return;
    try {
        auto lock = server->Lock();
        if (kind == 0) server->GetMouse().MoveTo(a, b);
        else if (kind == 3) server->GetMouse().MoveBy(a, b);
        else if (kind == 4) server->GetMouse().AnchorTo(a, b);
        else if (kind == 5) server->GetMouse().SetRawInputEnabled(a != 0);
        else if (kind == 1) server->GetMouse().SetButton(a, b != 0);
        else if (kind == 2) server->GetKeyboard().SetKeyState(static_cast<xorg::XKeycode>(a), b != 0);
    } catch (const std::exception& e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeOptions)(JNIEnv*, jclass, jlong handle, jboolean fullscreen,
                                                          jboolean cursor, jint fps, jfloat refresh) {
    auto server = session(handle); if (!server) return;
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetOptions(fullscreen, cursor, fps, refresh);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeMagnifierZoom)(JNIEnv*, jclass, jlong handle, jfloat zoom) {
    auto server = session(handle); if (!server) return;
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetMagnifierZoom(zoom);
}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(nativeError)(JNIEnv* env, jclass, jlong handle) {
    auto server = session(handle); if (!server) return env->NewStringUTF("");
    auto message = static_cast<xorg::NativeScreen&>(server->GetScreen()).LastError();
    return env->NewStringUTF(message.c_str());
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(nativeVisualOptions)(JNIEnv* env, jclass, jlong handle,
        jint filter, jint postFX, jint stretch, jfloat sharpness, jboolean frameGen,
        jstring cache, jint multiplier, jint targetRate, jint flowScale, jfloat refresh) {
    auto server = session(handle); if (!server) return 0;
    xorg::NativeVisualOptions options;
    options.filter = filter; options.postFX = postFX; options.stretch = stretch; options.sharpness = sharpness;
    options.frameGen = frameGen; options.multiplier = multiplier; options.targetRate = targetRate;
    options.flowScale = flowScale; options.refresh = refresh;
    if (cache) {
        JavaString path(env, cache);
        if (!path.text) return 0;
        options.frameGenCache = path.text;
    }
    return static_cast<xorg::NativeScreen&>(server->GetScreen()).SetVisualOptions(std::move(options));
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(nativeVisualState)(JNIEnv* env, jclass, jlong handle) {
    auto server = session(handle); if (!server) return nullptr;
    const auto state = static_cast<xorg::NativeScreen&>(server->GetScreen()).VisualState();
    const jlong values[] = {jlong(state[0]), jlong(state[1]), jlong(state[2])};
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeDiagnostics)(JNIEnv*, jclass, jlong handle, jboolean enabled, jboolean commandCacheEnabled) {
    auto server = session(handle); if (!server) return;
    auto& screen = static_cast<xorg::NativeScreen&>(server->GetScreen());
    screen.SetCommandCacheEnabled(commandCacheEnabled);
    screen.SetDiagnostics(enabled);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeUnviewableWMClasses)(JNIEnv* env, jclass, jlong handle, jobjectArray values) {
    auto server = session(handle); if (!server) return;
    std::vector<std::string> classes;
    const jsize count = values ? env->GetArrayLength(values) : 0;
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        if (env->ExceptionCheck()) return;
        if (!value) continue;
        {
            JavaString text(env, value);
            if (text.text) classes.emplace_back(text.text);
        }
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return;
    }
    auto lock = server->Lock();
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetUnviewableWMClasses(std::move(classes));
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeRefreshRates)(JNIEnv* env, jclass, jlong handle, jintArray values) {
    auto server = session(handle); if (!server || !values) return;
    const jsize count = env->GetArrayLength(values);
    if (count < 1 || count > 1024) return;
    std::vector<jint> input(count);
    env->GetIntArrayRegion(values, 0, count, input.data());
    if (env->ExceptionCheck()) return;
    std::vector<u16> rates;
    for (jint hz : input) if (hz > 0 && hz <= 1000) rates.push_back(hz);
    auto lock = server->Lock();
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetRandrRates(std::move(rates));
}

extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(nativeHasContent)(JNIEnv*, jclass, jlong handle) {
    auto server = session(handle); if (!server) return false;
    return static_cast<xorg::NativeScreen&>(server->GetScreen()).presentedCount.load() != 0;
}

namespace {
uint64_t windowHandle(xorg::Window* w) {
    for (; w; w = w->GetParent()) {
        auto atom = xorg::Atom::GetID("_NET_WM_HWND");
        auto* property = atom >= 0 ? w->GetProperty(atom) : nullptr;
        if (!property) continue;
        auto data = property->GetData(); uint64_t handle{};
        if (data.size() >= 4) memcpy(&handle, data.data(), std::min(data.size(), sizeof(handle)));
        if (handle) return handle;
    }
    return 0;
}
bool findHandle(xorg::Window* w, uint64_t handle) {
    if (w->IsMapped() && windowHandle(w) == handle) return true;
    for (auto* child : w->GetChildren()) if (findHandle(child, handle)) return true;
    return false;
}
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(nativeFocusHandle)(JNIEnv*, jclass, jlong handle) {
    auto server = session(handle); if (!server) return 0;
    auto lock = server->Lock(); return windowHandle(server->GetWindowManager().GetFocuseWindow());
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(nativeHasWindow)(JNIEnv*, jclass, jlong sessionHandle, jlong hwnd) {
    auto server = session(sessionHandle); if (!server || !hwnd) return false;
    auto lock = server->Lock(); return findHandle(server->GetWindowManager().GetRootWindow(), hwnd);
}

extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeHudEnabled)(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    auto server = session(handle); if (!server) return;
    auto lock = server->Lock();
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetHudEnabled(enabled);
}
extern "C" JNIEXPORT jobjectArray JNICALL JNI_NAME(nativeHudSnapshot)(JNIEnv* env, jclass, jlong handle) {
    auto server = session(handle); if (!server) return nullptr;
    std::string values[4];
    {
        auto lock = server->Lock();
        auto& screen = static_cast<xorg::NativeScreen&>(server->GetScreen());
        auto* r = server->GetResource(screen.HudWindow());
        auto* window = r && r->GetType() == xorg::XResourceType::Window ? r->As<xorg::Window>() : nullptr;
        for (auto* w = window; w; w = w->GetParent()) if (!w->IsMapped()) { window = nullptr; break; }
        values[0] = std::to_string(window ? window->id : 0);
        values[1] = std::to_string(screen.HudFrames());
        const char* names[] = {"_MESA_DRV_ENGINE_NAME", "_MESA_DRV_GPU_NAME"};
        for (int i = 0; window && i < 2; ++i) {
            auto atom = xorg::Atom::GetID(names[i]);
            auto* property = atom >= 0 ? window->GetProperty(atom) : nullptr;
            if (!property) continue;
            for (u8 c : property->GetData()) {
                if (!c || values[i+2].size() == 256) break;
                values[i+2] += c >= 32 && c < 127 ? char(c) : '?';
            }
        }
    }
    auto stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    auto result = env->NewObjectArray(4, stringClass, nullptr); env->DeleteLocalRef(stringClass);
    if (!result) return nullptr;
    for (int i = 0; i < 4; ++i) {
        auto value = env->NewStringUTF(values[i].c_str()); if (!value) return nullptr;
        env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}

