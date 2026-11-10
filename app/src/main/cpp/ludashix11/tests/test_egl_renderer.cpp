// Real Mesa/GLES rendering. Only Android's surface and AHB import boundaries are modeled.
#include "native_screen.h"
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <dlfcn.h>
#include <cassert>
#include <iostream>

static unsigned imports{}, destroyed{};
extern "C" EGLBoolean eglChooseConfig(EGLDisplay d, const EGLint *attrs, EGLConfig *configs, EGLint size,
                                      EGLint *count) {
    std::vector<EGLint> headless;
    for (size_t i = 0; attrs[i] != EGL_NONE; i += 2) {
        headless.push_back(attrs[i]);
        headless.push_back(attrs[i] == EGL_SURFACE_TYPE ? (attrs[i + 1] & ~EGL_WINDOW_BIT) : attrs[i + 1]);
    }
    headless.push_back(EGL_NONE);
    auto choose = reinterpret_cast<PFNEGLCHOOSECONFIGPROC>(dlsym(RTLD_NEXT, "eglChooseConfig"));
    return choose(d, headless.data(), configs, size, count);
}
extern "C" EGLSurface eglCreateWindowSurface(EGLDisplay d, EGLConfig c, EGLNativeWindowType, const EGLint *) {
    const EGLint size[] = {EGL_WIDTH, 1280, EGL_HEIGHT, 720, EGL_NONE};
    return eglCreatePbufferSurface(d, c, size);
}
extern "C" EGLClientBuffer eglGetNativeClientBufferANDROID(const AHardwareBuffer *buffer) {
    return const_cast<AHardwareBuffer *>(buffer);
}
extern "C" EGLImageKHR eglCreateImageKHR(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer buffer,
                                          const EGLint* attrs) {
    assert(attrs && attrs[0] == EGL_IMAGE_PRESERVED_KHR && attrs[1] == EGL_TRUE);
    ++imports; return buffer;
}
extern "C" EGLBoolean eglDestroyImageKHR(EGLDisplay, EGLImageKHR) {
    ++destroyed;
    return EGL_TRUE;
}
extern "C" void glEGLImageTargetTexture2DOES(GLenum target, GLeglImageOES value) {
    auto *buffer = static_cast<AHardwareBuffer *>(value);
    glTexImage2D(target, 0, GL_RGBA, buffer->desc.width, buffer->desc.height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                 buffer->pixels.data());
}
extern "C" EGLSyncKHR eglCreateSyncKHR(EGLDisplay d, EGLenum type, const EGLint *) {
    return eglCreateSync(d, type, nullptr);
}
extern "C" EGLint eglClientWaitSyncKHR(EGLDisplay d, EGLSyncKHR s, EGLint flags, EGLTimeKHR timeout) {
    return eglClientWaitSync(d, s, flags, timeout);
}
extern "C" EGLBoolean eglDestroySyncKHR(EGLDisplay d, EGLSyncKHR s) {
    return eglDestroySync(d, s);
}

namespace xorg {
std::unique_ptr<NativeRenderer> CreateNativeEGLRenderer(u16, u16, const NativeRendererConfig &);
}
static std::array<u8, 4> Pixel(int x, int y) {
    std::array<u8, 4> result{};
    glReadPixels(x, 719 - y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, result.data());
    assert(glGetError() == GL_NO_ERROR);
    return result;
}
int main() {
    using namespace xorg;
    NativeRendererConfig config;
    config.backend = NativeRendererConfig::Backend::EGL;
    {
        auto renderer = CreateNativeEGLRenderer(1280, 720, config);
        renderer->Surface(new ANativeWindow);
        renderer->Options(false, true, 0, 60, 1);
        NativeVisualOptions options;
        options.filter = 1;
        renderer->VisualOptions(options);
        auto image = std::make_shared<NativeDrawable>(32, 32, 24);
        image->FillRect(0, 0, 32, 32, 0xff112233);
        NativeFrame frame;
        frame.entries.push_back({image, nullptr, image->Pixels(), image->Revision(), 100, 50, true});
        assert(renderer->Draw(frame));
        glFinish();
        assert((Pixel(110, 60) == std::array<u8, 4>{0x11, 0x22, 0x33, 0xff}));
        assert((Pixel(10, 10) == std::array<u8, 4>{0, 0, 0, 0xff}));
        assert(renderer->PollCompleted(1) == 1);
        image->FillRect(0, 0, 32, 32, 0xff445566);
        frame.entries[0].pixels = image->Pixels();
        frame.entries[0].revision = image->Revision();
        frame.cursor = std::make_shared<NativeDrawable>(4, 4, 32);
        frame.cursor->FillRect(0, 0, 4, 4, 0x8000ff00);
        frame.cursorPixels = frame.cursor->Pixels();
        frame.cursorX = 110;
        frame.cursorY = 60;
        assert(renderer->Draw(frame));
        glFinish();
        const auto cursor = Pixel(111, 61);
        assert(cursor[0] >= 33 && cursor[0] <= 34 && cursor[1] >= 169 && cursor[1] <= 171 &&
               cursor[2] >= 50 && cursor[2] <= 51);
        assert((Pixel(120, 70) == std::array<u8, 4>{0x44, 0x55, 0x66, 0xff}));
        assert(renderer->Drain() == 1);
        auto *buffer = new AHardwareBuffer;
        buffer->desc.width = 32;
        buffer->desc.height = 32;
        buffer->desc.stride = 32;
        buffer->desc.layers = 1;
        buffer->pixels.resize(32 * 32 * 4);
        for (size_t i = 0; i < buffer->pixels.size(); i += 4) {
            buffer->pixels[i] = 200;
            buffer->pixels[i + 3] = 255;
        }
        auto direct = std::make_shared<NativeDrawable>(32, 32, 24, buffer);
        AHardwareBuffer_release(buffer);
        frame.entries[0] = {direct, nullptr, nullptr, direct->Revision(), 100, 50, true};
        frame.cursor.reset();
        assert(renderer->Draw(frame));
        glFinish();
        assert((Pixel(110, 60) == std::array<u8, 4>{200, 0, 0, 255}));
        assert(renderer->Draw(frame));
        glFinish();
        assert(imports == 1);
        assert(renderer->Drain() == 2);
        assert(renderer->VisualState().ready && !renderer->VisualState().supported);
        renderer->Surface(nullptr);
        renderer->Surface(new ANativeWindow);
        assert(renderer->Draw(frame));
        glFinish();
        assert(imports == 1);
        renderer->Drain();
    }
    assert(destroyed == imports);
    {
        config.swapRB = true;
        auto renderer = CreateNativeEGLRenderer(640, 480, config);
        renderer->Surface(new ANativeWindow);
        auto image = std::make_shared<NativeDrawable>(640, 480, 24);
        image->FillRect(0, 0, 640, 480, 0xff112233);
        NativeFrame frame;
        frame.entries.push_back({image, nullptr, image->Pixels(), image->Revision(), 0, 0, true});
        assert(renderer->Draw(frame));
        glFinish();
        assert((Pixel(0, 10) == std::array<u8, 4>{0, 0, 0, 255}));
        assert((Pixel(170, 10) == std::array<u8, 4>{0x33, 0x22, 0x11, 255}));
        renderer->Drain();
    }
    std::cout << "PASS: real GLES shader pixels, BGRA/RGBA, alpha cursor, CPU updates, cached AHB imports, "
                 "fences and surface recreation\n";
}
