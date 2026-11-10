#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/window_manager.h"

namespace xorg {
void XClient::ProcComposite() {
    const auto minor = GetRequestData<xReq>()->data;
    if (minor == 0) {
        struct Request { xReq header; u32 major, minor; };
        CHECK_REQUEST_SIZE_STRICT(Request);
        struct Reply { u8 type{}, pad{}; u16 sequence{}; u32 length{}, major{}, minor{}, pad2[4]{}; } reply;
        static_assert(sizeof(reply) == 32);
        reply.type = X_Reply; reply.sequence = sequence;
        const auto* r = GetRequestData<Request>();
        reply.minor = r->major ? 1 : std::min(1u, r->minor);
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    if (minor == 1 || minor == 3) {
        struct Request { xReq header; u32 window; u8 update; u8 pad[3]; };
        CHECK_REQUEST_SIZE_STRICT(Request);
        auto* r = GetRequestData<Request>();
        auto* window = GetResourceAs<Window>(r->window);
        if (!window) throw BadWindowError(r->window);
        server.GetWindowManager().RedirectComposite(this, window, r->update, minor == 1);
        return;
    }
    // Match public Ludashi 0.1: no RedirectSubwindows/NameWindowPixmap claims.
    throw XRequestError(BadImplementation, 0);
}
}
