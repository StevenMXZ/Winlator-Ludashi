// Direct-rendering GLX metadata, matching the Java GLXExtension contract.
// GL contexts and commands execute in the client's Mesa driver; buffers reach
// NativeScreen through DRI3/AHB + Present, without server-side GL or readback.
#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/extension.h"
#include "core/window.h"
#include "core/pixmap.h"
#include "core/drawable.h"
#include "core/visual.h"
#include "core/xscreen.h"
#include "native_screen.h"
#include <algorithm>
#include <atomic>
#include <array>
#include <cstring>
#include <iterator>
#include <string>
#include <vector>

namespace xorg {
namespace {
struct Context final : XResource {
    Context(u32 id, u32 visual, u32 config, bool direct, u32 major = 1, u32 minor = 0,
            u32 flags = 0, u32 profile = 1)
        : XResource(XResourceType::GLXContext, id), visual(visual), config(config),
          major(major), minor(minor), flags(flags), profile(profile), direct(direct) {}
    u32 visual, config, major, minor, flags, profile;
    bool direct;
};
struct GLDrawable final : XResource {
    GLDrawable(u32 id, u32 config, u32 target, bool pixmap, sp<Drawable> image)
        : XResource(XResourceType::GLXDrawable, id), config(config), target(target), pixmap(pixmap), image(std::move(image)) {}
    u32 config, target;
    bool pixmap;
    sp<Drawable> image;
};
struct Reply {
    u8 type{X_Reply}, pad{};
    u16 sequenceNumber{};
    u32 length{};
    u32 values[6]{};
};
static_assert(sizeof(Reply) == 32);
struct Config { u32 depth, stencil, doubleBuffer; };
constexpr Config configs[] = {{0,0,1}, {0,0,0}, {24,0,0}, {24,8,0}, {24,0,1}, {24,8,1}};
}
// DRI3 PixmapFromBuffer can name the GLX window XID rather than the
// underlying X11 window. Keep the wrapper alive and validate its target.
Drawable* XClient::ResolveGLXDrawable(u32 id) const {
    auto* resource = GetResource(id);
    if (!resource || resource->GetType() != XResourceType::GLXDrawable)
        return nullptr;
    const auto* gl = resource->As<GLDrawable>();
    // GLXWindow objects follow the live X11 drawable across window resizes.
    // GLXPixmap objects retain the original pixmap until explicitly destroyed.
    return gl->pixmap ? gl->image.get() : GetDrawable(gl->target);
}
void XClient::ProcGLX() {
    const auto minor = GetRequestData<xReq>()->data;
    if (static_cast<NativeScreen&>(server.GetScreen()).DiagnosticsEnabled()) {
        // Mesa/Wine GLX initialization happens once; keep these logs out of
        // the normal, latency-sensitive Present path and bound noisy clients.
        static std::atomic<unsigned> traced{0};
        if (traced.fetch_add(1, std::memory_order_relaxed) < 48)
            LOGI("Native GLX request opcode={} bytes={}", static_cast<unsigned>(minor), request_size);
    }
    auto* extension = server.GetExtensionManager().FindExtension("GLX");
    auto error = [&](u8 offset, u32 id) { return XRequestError(extension->error_base + offset, id); };
    auto minimum = [&](size_t size) { if (request_size < size) throw BadLengthError(); };
    auto word = [&](size_t offset) {
        minimum(offset + 4); u32 value; memcpy(&value, request_buffer.data() + offset, 4); return value;
    };
    auto screen = [&](u32 value) { if (value) throw BadValueError(value); };
    auto newID = [&](u32 id) { CheckResourceID(id); if (GetResource(id)) throw BadIdChoiceError(id); };
    auto config = [&](u32 id) -> const Config& {
        if (!id || id > std::size(configs)) throw error(9, id);
        return configs[id - 1];
    };
    auto context = [&](u32 id) {
        auto* r = GetResource(id);
        if (!r || r->GetType() != XResourceType::GLXContext) throw error(0, id);
        return r->As<Context>();
    };
    auto shared = [&](u32 id) { if (id) (void)context(id); };
    auto reply = [&](const std::vector<u32>& values, u32 first = 0, u32 second = 0) {
        Reply r{}; r.sequenceNumber = sequence; r.length = values.size(); r.values[0] = first; r.values[1] = second;
        Write(&r, sizeof(r)); if (!values.empty()) Write(values.data(), values.size() * 4); Flush();
    };
    auto serverString = [&](u32 name) {
        std::string value;
        if (name == 1) value = "Winlator";
        else if (name == 2) value = "1.4";
        else if (name == 3) value = "GLX_ARB_create_context GLX_ARB_create_context_profile";
        else throw BadValueError(name);
        value.push_back('\0');
        Reply r{}; r.sequenceNumber = sequence; r.length = (value.size() + 3) / 4;
        r.values[1] = value.size();
        Write(&r, sizeof(r)); Write(value.data(), value.size()); WritePad((-value.size()) & 3); Flush();
    };
    switch (minor) {
    case 7: // QueryVersion
        minimum(12); { Reply r{}; r.sequenceNumber = sequence; r.values[0] = 1; r.values[1] = 4; WriteImmediately(&r, sizeof(r)); } return;
    case 18: // QueryExtensionsString
        screen(word(4)); serverString(3); return;
    case 19: // QueryServerString
        screen(word(4)); serverString(word(8)); return;
    case 14: { // GetVisualConfigs (18 fixed properties per visual)
        screen(word(4)); std::vector<u32> values;
        values.reserve(std::size(configs) * 18);
        for (const auto& c : configs) {
            const u32 visual[] = {server.GetRootVisual()->id, 4, 1, 8,8,8,8, 0,0,0,0,
                                  c.doubleBuffer, 0, 32, c.depth, c.stencil, 0,0};
            values.insert(values.end(), std::begin(visual), std::end(visual));
        }
        reply(std::move(values), std::size(configs), 18); return;
    }
    case 21: { // GetFBConfigs (attribute/value pairs)
        screen(word(4)); std::vector<u32> values;
        // Java GLXExtension advertises 44 attribute/value pairs per FBConfig,
        // including 16 reserved pairs; some Wine/Mesa paths parse the full set.
        constexpr size_t kAttribsPerConfig = 44;
        values.reserve(std::size(configs) * kAttribsPerConfig * 2);
        for (u32 i = 0; i < std::size(configs); ++i) {
            const auto& c = configs[i];
            const u32 pairs[] = {
                0x800b,server.GetRootVisual()->id, 0x8013,i+1, 0x8012,1, 4,1, 0x8011,1,
                5,c.doubleBuffer, 6,0, 2,32, 3,0, 7,0, 8,8, 9,8, 10,8, 11,8,
                14,0, 15,0, 16,0, 17,0, 12,c.depth, 13,c.stencil,
                0x22,0x8002, 0x23,0x8000, 0x24,0, 0x25,0, 0x26,0, 0x27,0, 0x28,0,
                0x8010,3};
            static_assert(std::size(pairs) == 28 * 2);
            values.insert(values.end(), std::begin(pairs), std::end(pairs));
            values.insert(values.end(), (kAttribsPerConfig - std::size(pairs) / 2) * 2, 0);
        }
        reply(values, std::size(configs), kAttribsPerConfig); return;
    }
    case 20: // ClientInfo
        minimum(16); if (u64(word(12)) > request_size - 16) throw BadLengthError(); return;
    case 33: case 35: { // SetClientInfoARB / SetClientInfo2ARB
        minimum(24);
        u64 end = 24 + u64(word(12)) * (minor == 35 ? 12 : 8);
        end += u64(word(16)) + word(20);
        if (end > request_size) throw BadLengthError(); return;
    }
    case 3: case 24: case 34: { // CreateContext / CreateNewContext / CreateContextAttribsARB
        minimum(minor == 3 ? 24 : 28);
        const auto id = word(4); newID(id);
        u32 visual = server.GetRootVisual()->id, cfg = 1, share{};
        u32 major = 1, minorVersion = 0, flags = 0, profile = 1;
        bool direct{};
        if (minor == 3) {
            if (word(8) != visual) throw BadValueError(word(8));
            screen(word(12)); share = word(16); direct = request_buffer[20] != 0;
        } else {
            cfg = word(8); (void)config(cfg); screen(word(12));
            if (minor == 24) {
                if (word(16) != 0x8014) throw BadValueError(word(16));
                share = word(20); direct = request_buffer[24] != 0;
            } else {
                share = word(16); direct = request_buffer[20] != 0;
                const u32 count = word(24);
                if (28 + u64(count) * 8 != request_size) throw BadLengthError();
                for (u32 i = 0; i < count; ++i) {
                    u32 attr = word(28 + i * 8), value = word(32 + i * 8);
                    switch (attr) {
                    case 0x2091: major = value; break;
                    case 0x2092: minorVersion = value; break;
                    case 0x2094: flags = value; if (flags & ~3u) throw BadValueError(flags); break;
                    case 0x8011: if (value != 0x8014) throw BadValueError(value); break;
                    case 0x9126: profile = value; if (value != 1 && value != 2) throw error(13, value); break;
                    default: throw BadValueError(attr);
                    }
                }
            }
        }
        if (minor == 34) {
            // Match the Java GLX metadata contract. Actual OpenGL version and
            // profile support are ultimately decided by the client Mesa driver.
            if (major < 1 || major > 4 ||
                (major == 1 && minorVersion > 5) ||
                (major == 2 && minorVersion > 1) ||
                (major == 3 && minorVersion > 3) ||
                (major < 3 && (flags & 2u))) throw BadMatchError();
        }
        shared(share);
        // The Mesa/Winlator client uses direct rendering. GLX indirect contexts
        // require server-side GL command execution, which this native server
        // deliberately does not implement; report a protocol error instead of
        // accepting a context that cannot render.
        if (!direct) throw error(0, id);
        AddResource(std::make_shared<Context>(id, visual, cfg, direct,
                                              major, minorVersion, flags, profile)); return;
    }
    case 4: { // DestroyContext
        if (request_size != 8) throw BadLengthError();
        auto id = word(4); (void)context(id); RemoveResource(id); return;
    }
    case 5: case 26: { // MakeCurrent / MakeContextCurrent (direct GLX metadata)
        const size_t expected = minor == 5 ? 16 : 20;
        if (request_size != expected) throw BadLengthError();
        const u32 ctx = word(minor == 5 ? 8 : 16);
        const u32 draw = word(minor == 5 ? 4 : 8);
        const u32 read = minor == 5 ? draw : word(12);
        if (ctx) {
            if (!context(ctx)->direct) throw error(0, ctx);
            for (u32 id : {draw, read})
                if (!id || (!GetDrawable(id) && !ResolveGLXDrawable(id))) throw error(2, id);
        } else if (draw || read) throw BadMatchError();
        // Mesa owns current GL contexts and performs all GPU work in-process.
        // The tag is only relevant for indirect command streams, which we do
        // not implement; never submit or wait for GPU work on this path.
        Reply r{}; r.sequenceNumber = sequence; r.values[0] = ctx ? 1 : 0;
        WriteImmediately(&r, sizeof(r)); return;
    }
    case 8: case 9: { // WaitGL / WaitX: already ordered by the direct client
        if (request_size != 8) throw BadLengthError();
        return;
    }
    case 6: { auto* c = context(word(4)); Reply r{}; r.sequenceNumber = sequence; r.values[0] = c->direct; WriteImmediately(&r, sizeof(r)); return; }
    case 25: { // QueryContext
        auto* c = context(word(4)); reply({0x800a,0, 0x800b,c->visual, 0x8013,c->config, 0x8011,0x8014}, 4); return;
    }
    case 13: { // CreateGLXPixmap (GLX 1.2 compatibility)
        if (request_size != 20) throw BadLengthError();
        screen(word(4));
        const u32 visual = word(8), target = word(12), id = word(16);
        if (visual != server.GetRootVisual()->id) throw BadValueError(visual);
        newID(id);
        auto* pixmap = GetResourceAs<Pixmap>(target);
        if (!pixmap) throw BadPixmapError(target);
        AddResource(std::make_shared<GLDrawable>(id, 1, target, true,
                                                  pixmap->SharedDrawable()));
        return;
    }
    case 22: case 31: { // CreatePixmap / CreateWindow
        minimum(24); screen(word(4)); u32 cfg = word(8); (void)config(cfg);
        const u32 target = word(12), id = word(16), count = word(20); newID(id);
        if (24 + u64(count) * 8 != request_size) throw BadLengthError();
        sp<Drawable> image;
        if (minor == 22) {
            auto* p = GetResourceAs<Pixmap>(target); if (!p) throw BadPixmapError(target); image = p->SharedDrawable();
        } else {
            auto* w = GetResourceAs<Window>(target); if (!w || w->IsInputOnly()) throw BadWindowError(target); image = w->SharedDrawable();
        }
        AddResource(std::make_shared<GLDrawable>(id, cfg, target, minor == 22, std::move(image))); return;
    }
    case 15: case 23: case 32: { // DestroyGLXPixmap / DestroyPixmap / DestroyWindow
        if (request_size != 8) throw BadLengthError();
        const u32 id = word(4);
        auto* r = GetResource(id);
        const bool isPixmap = minor == 15 || minor == 23;
        if (!r || r->GetType() != XResourceType::GLXDrawable || r->As<GLDrawable>()->pixmap != isPixmap)
            throw error(isPixmap ? 3 : 12, id);
        RemoveResource(id); return;
    }
    case 29: case 30: { // Get/ChangeDrawableAttributes
        u32 id = word(4), cfg = 1, kind = 1; Drawable* d{};
        auto* r = GetResource(id);
        if (r && r->GetType() == XResourceType::GLXDrawable) {
            auto* gl = r->As<GLDrawable>(); cfg = gl->config; kind = gl->pixmap ? 2 : 1;
            d = gl->pixmap ? gl->image.get() : GetDrawable(gl->target);
        } else d = GetDrawable(id);
        if (!d) throw error(2, id);
        if (minor == 30) { if (12 + u64(word(8)) * 8 != request_size) throw BadLengthError(); return; }
        reply({0x8013,cfg, 0x8010,kind, 0x8011,1, 0x801d,d->GetWidth(), 0x801e,d->GetHeight()}, 5); return;
    }
    default:
        if (static_cast<NativeScreen&>(server.GetScreen()).DiagnosticsEnabled())
            LOGE("Native GLX unsupported request opcode={}", static_cast<unsigned>(minor));
        throw BadRequestError();
    }
}
}
