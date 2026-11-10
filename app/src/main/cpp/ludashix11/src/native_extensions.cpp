#include "native_screen.h"
#include "base/fd.h"
#include "core/extension.h"
#include "core/pixmap.h"
#include "core/window.h"
#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include <X11/extensions/dri3proto.h>
#include <X11/extensions/presentproto.h>
#include <X11/extensions/syncproto.h>
#include <sys/socket.h>
#include <cerrno>

namespace xorg {
XClient::BufferImport::~BufferImport() {
    if (worker.joinable()) {
        if (!done.load(std::memory_order_acquire)) shutdown(transfer.Handle(), SHUT_RDWR);
        worker.join();
    }
    if (buffer) AHardwareBuffer_release(buffer);
}
void XClient::FinishDRI3Import() {
    auto import = std::move(pendingImport);
    import->worker.join();
    try {
        if (!import->buffer) throw BadMatchError();
        if (GetResource(import->pixmap)) throw BadIdChoiceError(import->pixmap);
        auto p = std::make_shared<Pixmap>(server.GetScreen(), import->pixmap,
                                        import->width, import->height, import->buffer);
        AddResource(p);
        auto& screen = static_cast<NativeScreen&>(server.GetScreen());
        if (screen.DiagnosticsEnabled()) screen.RecordDri3Import();
    } catch (const XRequestError& e) {
        xError error{};
        error.type = X_Error; error.errorCode = e.code; error.sequenceNumber = import->sequence;
        error.resourceID = e.data; error.majorCode = import->opcode; error.minorCode = import->minor;
        WriteImmediately(&error, sizeof(error));
    }
}
void XClient::ProcDRI3() {
    auto* req = GetRequestData<xReq>();
    switch (req->data) {
    case X_DRI3QueryVersion: {
        CHECK_REQUEST_SIZE_STRICT(xDRI3QueryVersionReq);
        xDRI3QueryVersionReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.majorVersion = 1; reply.minorVersion = 2;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_DRI3Open: {
        CHECK_REQUEST_SIZE_STRICT(xDRI3OpenReq);
        const u32 id = GetRequestData<xDRI3OpenReq>()->drawable;
        // Mesa may ask to open a core drawable or the GLX drawable wrapper.
        if (!GetDrawable(id) && !ResolveGLXDrawable(id)) throw BadDrawableError(id);
        // Android transport uses an AHB handle socket, no DRM render node.
        xDRI3OpenReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case xDRI3GetSupportedModifiers: {
        CHECK_REQUEST_SIZE_STRICT(xDRI3GetSupportedModifiersReq);
        xDRI3GetSupportedModifiersReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_DRI3PixmapFromBuffer:
    case xDRI3PixmapFromBuffers: {
        u32 pixmap{}, drawable{}; u16 width{}, height{}; u8 depth{};
        if (req->data == X_DRI3PixmapFromBuffer) {
            CHECK_REQUEST_SIZE_STRICT(xDRI3PixmapFromBufferReq);
            auto* r = GetRequestData<xDRI3PixmapFromBufferReq>();
            pixmap = r->pixmap; drawable = r->drawable; width = r->width; height = r->height; depth = r->depth;
        } else {
            CHECK_REQUEST_SIZE_STRICT(xDRI3PixmapFromBuffersReq);
            auto* r = GetRequestData<xDRI3PixmapFromBuffersReq>();
            if (r->num_buffers != 1) throw BadMatchError();
            pixmap = r->pixmap; drawable = r->window; width = r->width; height = r->height; depth = r->depth;
        }
        base::ScopedFD transfer(ConsumeRequestFd());
        if (!GetDrawable(drawable) && !ResolveGLXDrawable(drawable))
            throw BadDrawableError(drawable);
        CheckResourceID(pixmap);
        if (GetResource(pixmap)) throw BadIdChoiceError(pixmap);
        if (!width || !height || (depth != 24 && depth != 32)) {
            if (static_cast<NativeScreen&>(server.GetScreen()).DiagnosticsEnabled())
                LOGE("DRI3 invalid pixmap format: drawable={} pixmap={} size={}x{} depth={}",
                     drawable, pixmap, width, height, static_cast<int>(depth));
            throw BadMatchError();
        }
        // Only this client's request stream is suspended. Transport I/O must
        // not hold XServer::Lock or stall input and unrelated X11 clients.
        auto import = std::make_unique<BufferImport>();
        import->transfer.Reset(transfer.Release());
        import->pixmap = pixmap; import->width = width; import->height = height;
        import->sequence = sequence; import->opcode = req->reqType; import->minor = req->data;
        auto* work = import.get();
        work->worker = std::thread([work, server = &server] {
            // A broken producer must not retain a worker indefinitely. Client
            // destruction also shuts down the socket before joining this thread.
            timeval timeout{5, 0};
            setsockopt(work->transfer.Handle(), SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            setsockopt(work->transfer.Handle(), SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
            const u8 ready = 1;
            ssize_t sent;
            do { sent = send(work->transfer.Handle(), &ready, 1, MSG_NOSIGNAL); }
            while (sent < 0 && errno == EINTR);
            int received = -1;
            try {
                if (sent == 1) received = AHardwareBuffer_recvHandleFromUnixSocket(work->transfer.Handle(), &work->buffer);
            } catch (...) { /* Report transport/allocation failure on the X11 thread. */ }
            if (received != 0) {
                if (work->buffer) AHardwareBuffer_release(work->buffer);
                work->buffer = nullptr;
            }
            work->done.store(true, std::memory_order_release);
            server->NotifyScreen();
        });
        pendingImport = std::move(import);
        return;
    }
    default: throw BadRequestError();
    }
}
void XClient::ProcPresent() {
    auto& screen = static_cast<NativeScreen&>(server.GetScreen());
    auto* req = GetRequestData<xReq>();
    switch (req->data) {
    case X_PresentQueryVersion: {
        CHECK_REQUEST_SIZE_STRICT(xPresentQueryVersionReq);
        xPresentQueryVersionReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        // Match Java PresentExtension (1.2): Mesa GLX/Zink requires the
        // advertised Present minor version >= 2 alongside DRI3 1.2 before
        // enabling its X11/Kopper path. No DRM modifiers are fabricated.
        reply.majorVersion = 1; reply.minorVersion = 2;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_PresentQueryCapabilities: {
        CHECK_REQUEST_SIZE_STRICT(xPresentQueryCapabilitiesReq);
        xPresentQueryCapabilitiesReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        // No ASYNC or explicit GPU fence capability is advertised.
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_PresentSelectInput: {
        CHECK_REQUEST_SIZE_STRICT(xPresentSelectInputReq);
        auto* r = GetRequestData<xPresentSelectInputReq>();
        if (!GetResourceAs<Window>(r->window)) throw BadWindowError(r->window);
        auto* existing = GetResource(r->eid);
        if (existing) {
            if (existing->GetType() != XResourceType::PresentSubscription ||
                existing->As<Subscription>()->client != this) throw BadIdChoiceError(r->eid);
            if (existing->As<Subscription>()->window != r->window) throw BadMatchError();
            existing->As<Subscription>()->mask = r->eventMask;
            screen.Subscribe(std::static_pointer_cast<Subscription>(ShareResource(r->eid)));
            return;
        }
        CheckResourceID(r->eid);
        auto sub = std::make_shared<Subscription>(r->eid, this, r->window, r->eventMask);
        AddResource(sub); screen.Subscribe(std::move(sub)); return;
    }
    case X_PresentPixmap: {
        CHECK_REQUEST_SIZE(xPresentPixmapReq);
        auto* r = GetRequestData<xPresentPixmapReq>();
        auto* window = GetResourceAs<Window>(r->window);
        auto* pixmap = GetResourceAs<Pixmap>(r->pixmap);
        if (!window) throw BadWindowError(r->window);
        if (!pixmap) throw BadPixmapError(r->pixmap);
        for (u32 id : {r->wait_fence, r->idle_fence}) {
            auto* f = GetResource(id);
            if (id && (!f || f->GetType() != XResourceType::Fence)) throw BadValueError(id);
        }
        auto ticket = std::make_shared<PresentTicket>();
        ticket->window = r->window; ticket->pixmap = r->pixmap; ticket->serial = r->serial;
        ticket->waitFence = r->wait_fence; ticket->idleFence = r->idle_fence; ticket->opcode = req->reqType;
        ticket->x = r->x_off; ticket->y = r->y_off;
        ticket->drawable = std::static_pointer_cast<NativeDrawable>(pixmap->SharedDrawable());
        screen.Present(std::move(ticket)); return;
    }
    default: throw BadRequestError();
    }
}
void XClient::ProcSync() {
    auto* req = GetRequestData<xReq>();
    auto get = [&](u32 id) {
        auto* r = GetResource(id);
        if (!r || r->GetType() != XResourceType::Fence) throw BadValueError(id);
        return r->As<NativeFence>();
    };
    switch (req->data) {
    case X_SyncInitialize: {
        CHECK_REQUEST_SIZE_STRICT(xSyncInitializeReq);
        xSyncInitializeReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.majorVersion = 3; reply.minorVersion = 1;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_SyncListSystemCounters: {
        xSyncListSystemCountersReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_SyncCreateFence: {
        CHECK_REQUEST_SIZE_STRICT(xSyncCreateFenceReq);
        auto* r = GetRequestData<xSyncCreateFenceReq>();
        if (!GetDrawable(r->d)) throw BadDrawableError(r->d);
        CheckResourceID(r->fid);
        if (GetResource(r->fid)) throw BadIdChoiceError(r->fid);
        AddResource(std::make_shared<NativeFence>(r->fid, r->initially_triggered)); return;
    }
    case X_SyncTriggerFence:
    case X_SyncResetFence:
    case X_SyncDestroyFence:
    case X_SyncQueryFence: {
        CHECK_REQUEST_SIZE_STRICT(xSyncTriggerFenceReq);
        u32 id = GetRequestData<xSyncTriggerFenceReq>()->fid;
        auto* f = get(id);
        if (req->data == X_SyncDestroyFence) RemoveResource(id);
        else if (req->data == X_SyncQueryFence) {
            xSyncQueryFenceReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
            reply.triggered = f->triggered; WriteImmediately(&reply, sizeof(reply));
        } else {
            const bool triggered = req->data == X_SyncTriggerFence;
            const bool changed = f->triggered != triggered;
            f->triggered = triggered;
            if (changed && triggered) static_cast<NativeScreen&>(server.GetScreen()).FenceSignaled();
        }
        return;
    }
    case X_SyncAwaitFence: {
        if (request_size < 8 || request_size % 4) throw BadLengthError();
        bool any{}; std::vector<u32> ids;
        for (size_t offset = 4; offset < request_size; offset += 4) {
            u32 id; memcpy(&id, request_buffer.data() + offset, 4);
            any |= get(id)->triggered; ids.push_back(id);
        }
        if (!any) { await_fences = std::move(ids); server.AddSyncWaiter(); }
        return;
    }
    default: throw BadRequestError();
    }
}
}

