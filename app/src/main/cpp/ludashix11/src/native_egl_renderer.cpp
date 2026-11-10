#include "native_screen.h"
#include "native_backend_geometry.h"
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <unordered_set>

namespace xorg {
namespace {
void Require(bool value, const char *message) {
    if (!value)
        throw std::runtime_error(message);
}
GLuint Shader(GLenum kind, const char *source) {
    GLuint shader = glCreateShader(kind);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled{};
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        glDeleteShader(shader);
        throw std::runtime_error("Cannot compile X++ EGL shader");
    }
    return shader;
}
class EGLNativeRenderer final : public NativeRenderer {
    struct Texture {
        GLuint name{};
        EGLImageKHR image{EGL_NO_IMAGE_KHR};
        AHardwareBuffer *buffer{};
        u64 revision{}, used{};
        int width{}, height{};
        std::weak_ptr<NativeDrawable> owner;
    };
    int width, height, outerW{}, outerH{}, filter{};
    NativeRendererConfig config;
    ANativeWindow *window{};
    EGLDisplay display{EGL_NO_DISPLAY};
    EGLConfig eglConfig{};
    EGLContext context{EGL_NO_CONTEXT};
    EGLSurface surface{EGL_NO_SURFACE};
    GLuint program{}, quadBuffer{};
    GLint rect{}, view{}, swap{}, alpha{}, uvMax{};
    bool fullscreen{}, cursorVisible{true};
    float zoom{1.f};
    u64 serial{};
    NativeMagnifierPan pan;
    NativeBackendGeometry geometry;
    std::unordered_map<u64, Texture> cpuTextures;
    std::unordered_map<AHardwareBuffer *, Texture> imports;
    std::mutex pendingMutex;
    std::deque<EGLSyncKHR> pending;
    void Delete(Texture &texture, bool current = true) {
        if (current && texture.name)
            glDeleteTextures(1, &texture.name);
        if (texture.image != EGL_NO_IMAGE_KHR)
            eglDestroyImageKHR(display, texture.image);
        if (texture.buffer)
            AHardwareBuffer_release(texture.buffer);
    }
    void Initialize() {
        if (context != EGL_NO_CONTEXT)
            return;
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        try {
            Require(display != EGL_NO_DISPLAY && eglInitialize(display, nullptr, nullptr),
                    "Cannot initialize X++ EGL");
            const EGLint attrs[] = {EGL_SURFACE_TYPE,
                                    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                                    EGL_RENDERABLE_TYPE,
                                    EGL_OPENGL_ES2_BIT,
                                    EGL_RED_SIZE,
                                    8,
                                    EGL_GREEN_SIZE,
                                    8,
                                    EGL_BLUE_SIZE,
                                    8,
                                    EGL_ALPHA_SIZE,
                                    8,
                                    EGL_NONE};
            EGLint count{};
            Require(eglChooseConfig(display, attrs, &eglConfig, 1, &count) && count,
                    "No X++ EGL configuration");
            const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
            context = eglCreateContext(display, eglConfig, EGL_NO_CONTEXT, ctx);
            Require(context != EGL_NO_CONTEXT, "Cannot create X++ GLES2 context");
        } catch (...) {
            if (display != EGL_NO_DISPLAY)
                eglTerminate(display);
            display = EGL_NO_DISPLAY;
            throw;
        }
    }
    void InitializeProgram() {
        if (program)
            return;
        constexpr const char *vertex = R"(#version 100
            precision highp float;
            attribute vec2 corner;
            uniform vec4 rect; uniform vec2 view; uniform vec2 uvMax;
            varying mediump vec2 uv;
            void main() {
                vec2 q = corner;
                vec2 p = rect.xy + q * rect.zw;
                gl_Position = vec4(p.x / view.x * 2. - 1., 1. - p.y / view.y * 2., 0., 1.);
                uv = q * uvMax;
            })";
        constexpr const char *fragment = R"(#version 100
            precision mediump float;
            uniform sampler2D image; uniform bool swapColors; uniform bool cursor;
            varying mediump vec2 uv;
            void main() {
                vec4 color = texture2D(image, uv);
                if (swapColors) color = color.bgra;
                if (!cursor) color.a = 1.;
                gl_FragColor = color;
            })";
        GLuint vs = Shader(GL_VERTEX_SHADER, vertex), fs{};
        try {
            fs = Shader(GL_FRAGMENT_SHADER, fragment);
        } catch (...) {
            glDeleteShader(vs);
            throw;
        }
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glBindAttribLocation(program, 0, "corner");
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint linked{};
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (!linked) {
            glDeleteProgram(program);
            program = 0;
            throw std::runtime_error("Cannot link X++ EGL compositor");
        }
        rect = glGetUniformLocation(program, "rect");
        view = glGetUniformLocation(program, "view");
        swap = glGetUniformLocation(program, "swapColors");
        alpha = glGetUniformLocation(program, "cursor");
        uvMax = glGetUniformLocation(program, "uvMax");
        constexpr float corners[] = {0, 0, 1, 0, 0, 1, 1, 1};
        glGenBuffers(1, &quadBuffer);
        glBindBuffer(GL_ARRAY_BUFFER, quadBuffer);
        glBufferData(GL_ARRAY_BUFFER, sizeof(corners), corners, GL_STATIC_DRAW);
    }
    Texture &Upload(const sp<NativeDrawable> &d, const sp<const std::vector<u8>> &pixels, u64 revision) {
        auto *buffer = d->HardwareBuffer();
        Texture &texture = buffer ? imports[buffer] : cpuTextures[d->ID()];
        if (!texture.name) {
            try {
                glGenTextures(1, &texture.name);
                glBindTexture(GL_TEXTURE_2D, texture.name);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                if (buffer) {
                    const EGLClientBuffer client = eglGetNativeClientBufferANDROID(buffer);
                    Require(client != nullptr, "Cannot access X++ EGL hardware buffer");
                    const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
                    texture.image = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                                      client, attrs);
                    Require(texture.image != EGL_NO_IMAGE_KHR, "Cannot import X++ EGL hardware buffer");
                    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, texture.image);
                    Require(glGetError() == GL_NO_ERROR, "Cannot bind X++ EGL hardware buffer");
                    AHardwareBuffer_acquire(buffer);
                    texture.buffer = buffer;
                    AHardwareBuffer_Desc desc{};
                    AHardwareBuffer_describe(buffer, &desc);
                    texture.width = desc.width;
                    texture.height = desc.height;
                } else {
                    texture.width = d->GetWidth();
                    texture.height = d->GetHeight();
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, d->GetWidth(), d->GetHeight(), 0, GL_RGBA,
                                 GL_UNSIGNED_BYTE, nullptr);
                }
                Require(glGetError() == GL_NO_ERROR, "Cannot allocate X++ EGL texture");
            } catch (...) {
                Delete(texture);
                texture = {};
                throw;
            }
        }
        glBindTexture(GL_TEXTURE_2D, texture.name);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter == 1 ? GL_NEAREST : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter == 1 ? GL_NEAREST : GL_LINEAR);
        if (!buffer && texture.revision != revision) {
            Require(bool(pixels), "Missing X++ EGL pixel snapshot");
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, d->GetWidth(), d->GetHeight(), GL_RGBA, GL_UNSIGNED_BYTE,
                            pixels->data());
            Require(glGetError() == GL_NO_ERROR, "Cannot upload X++ EGL texture");
            texture.revision = revision;
        }
        texture.used = serial;
        texture.owner = d;
        return texture;
    }
    void Quad(const sp<NativeDrawable> &d, const sp<const std::vector<u8>> &pixels, u64 revision, int x,
              int y, bool cursor) {
        auto &texture = Upload(d, pixels, revision);
        glUniform4f(rect, geometry.ox + x * geometry.sx, geometry.oy + y * geometry.sy,
                    d->GetWidth() * geometry.sx, d->GetHeight() * geometry.sy);
        glUniform2f(uvMax, float(d->GetWidth()) / texture.width, float(d->GetHeight()) / texture.height);
        // CPU X11 storage is BGRA. EGLImage imports carry their own AHB format.
        glUniform1i(swap, (!texture.buffer) != (config.swapRB && !cursor));
        glUniform1i(alpha, cursor);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    size_t PollLocked(size_t limit) {
        size_t count{};
        while (count < limit && !pending.empty()) {
            const auto status = eglClientWaitSyncKHR(display, pending.front(), 0, 0);
            if (status == EGL_TIMEOUT_EXPIRED_KHR)
                break;
            Require(status == EGL_CONDITION_SATISFIED_KHR, "X++ EGL fence wait failed");
            eglDestroySyncKHR(display, pending.front());
            pending.pop_front();
            ++count;
        }
        return count;
    }

  public:
    EGLNativeRenderer(int w, int h, const NativeRendererConfig &options)
        : width(w), height(h), config(options) {}
    ~EGLNativeRenderer() override {
        try {
            Drain();
        } catch (...) {
        }
        if (display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT) {
            // A pbuffer also makes destruction safe after a detached Android surface.
            const EGLint attrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            auto cleanup = eglCreatePbufferSurface(display, eglConfig, attrs);
            const bool current = eglMakeCurrent(display, cleanup, cleanup, context);
            for (auto &[id, t] : cpuTextures)
                Delete(t, current);
            for (auto &[id, t] : imports)
                Delete(t, current);
            if (current) {
                if (program)
                    glDeleteProgram(program);
                if (quadBuffer)
                    glDeleteBuffers(1, &quadBuffer);
            }
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (cleanup != EGL_NO_SURFACE)
                eglDestroySurface(display, cleanup);
        }
        if (surface != EGL_NO_SURFACE)
            eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT)
            eglDestroyContext(display, context);
        if (display != EGL_NO_DISPLAY)
            eglTerminate(display);
        if (window)
            ANativeWindow_release(window);
    }
    void Surface(ANativeWindow *next) override {
        if (surface != EGL_NO_SURFACE) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(display, surface);
            surface = EGL_NO_SURFACE;
        }
        if (window)
            ANativeWindow_release(window);
        window = next;
        if (!next)
            return;
        Initialize();
        EGLint format{};
        eglGetConfigAttrib(display, eglConfig, EGL_NATIVE_VISUAL_ID, &format);
        ANativeWindow_setBuffersGeometry(window, 0, 0, format);
        surface = eglCreateWindowSurface(display, eglConfig, window, nullptr);
        Require(surface != EGL_NO_SURFACE && eglMakeCurrent(display, surface, surface, context),
                "Cannot attach X++ EGL surface");
        eglSwapInterval(display, 1); // Preserve the legacy EGL compositor swap behavior.
        InitializeProgram();
    }
    bool Draw(NativeFrame &frame, const NativeFrameLatch &latch) override {
        if (surface == EGL_NO_SURFACE)
            return false;
        bool submitted = false;
        struct FailedDraw {
            bool &submitted;
            ~FailedDraw() {
                if (!submitted)
                    glFinish();
            }
        } guard{submitted};
        if (latch)
            latch(frame);
        ++serial;
        outerW = ANativeWindow_getWidth(window);
        outerH = ANativeWindow_getHeight(window);
        if (outerW <= 0 || outerH <= 0)
            return false;
        geometry.Update(outerW, outerH, width, height, fullscreen, zoom, frame.cursorX, frame.cursorY, pan);
        glViewport(0, 0, outerW, outerH);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(geometry.left, outerH - geometry.bottom, geometry.right - geometry.left,
                  geometry.bottom - geometry.top);
        glUseProgram(program);
        glUniform2f(view, outerW, outerH);
        glActiveTexture(GL_TEXTURE0);
        glBindBuffer(GL_ARRAY_BUFFER, quadBuffer);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        for (const auto &e : frame.entries)
            Quad(e.drawable, e.pixels, e.revision, e.x, e.y, false);
        if (cursorVisible && frame.cursor && frame.cursorPixels) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            Quad(frame.cursor, frame.cursorPixels, frame.cursor->Revision(), frame.cursorX, frame.cursorY,
                 true);
        }
        auto fence = eglCreateSyncKHR(display, EGL_SYNC_FENCE_KHR, nullptr);
        Require(fence != EGL_NO_SYNC_KHR, "Cannot create X++ EGL retirement fence");
        glFlush();
        if (!eglSwapBuffers(display, surface)) {
            eglDestroySyncKHR(display, fence);
            throw std::runtime_error("X++ EGL swap failed");
        }
        {
            std::lock_guard lock(pendingMutex);
            pending.push_back(fence);
        }
        const auto prune = [&](auto &cache) {
            for (auto i = cache.begin(); i != cache.end();) {
                if (i->second.owner.expired() || serial - i->second.used > 32) {
                    Delete(i->second);
                    i = cache.erase(i);
                } else
                    ++i;
            }
        };
        prune(cpuTextures);
        prune(imports);
        submitted = true;
        return true;
    }
    bool ReadyToDraw() const override {
        auto *self = const_cast<EGLNativeRenderer *>(this);
        std::lock_guard lock(self->pendingMutex);
        return self->pending.size() < 3;
    }
    size_t PollCompleted(size_t limit) override {
        std::lock_guard lock(pendingMutex);
        return PollLocked(limit);
    }
    size_t WaitCompleted(size_t limit) override {
        EGLSyncKHR fence;
        {
            std::lock_guard lock(pendingMutex);
            if (!limit || pending.empty())
                return 0;
            fence = pending.front();
        }
        const auto status = eglClientWaitSyncKHR(display, fence, 0, 10000000);
        Require(status == EGL_CONDITION_SATISFIED_KHR || status == EGL_TIMEOUT_EXPIRED_KHR,
                "X++ EGL fence wait failed");
        return PollCompleted(limit);
    }
    size_t Drain() override {
        size_t count{};
        for (;;) {
            {
                std::lock_guard lock(pendingMutex);
                if (pending.empty())
                    return count;
            }
            count += WaitCompleted(1);
        }
    }
    void Options(bool fs, bool visible, int, float, float value) override {
        fullscreen = fs;
        cursorVisible = visible;
        if (zoom != value)
            pan.Reset();
        zoom = value;
    }
    void VisualOptions(const NativeVisualOptions &options) override {
        filter = options.filter == 1 ? 1 : 0;
    }
    NativeVisualState VisualState() const override {
        return {surface != EGL_NO_SURFACE, false, false, 0};
    }
};
} // namespace
std::unique_ptr<NativeRenderer> CreateNativeEGLRenderer(u16 w, u16 h, const NativeRendererConfig &config) {
    return std::make_unique<EGLNativeRenderer>(w, h, config);
}
} // namespace xorg
