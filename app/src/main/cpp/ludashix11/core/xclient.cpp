#include <sys/ioctl.h>
#include <unistd.h>
#include <X11/Xproto.h>
#include "base/alignment.h"
#include "base/log.h"
#include "core/exceptions.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "native_screen.h"
#include "core/selection_manager.h"
#include <cerrno>
#include <sys/socket.h>
#include "core/xresource.h"
#include "core/xserver.h"
#include "X11/extensions/bigreqsproto.h"

namespace xorg {

XClient::XClient(XServer& server, int fd) : server(server), socket_fd(fd), input_stream(fd) {
    request_buffer.resize(4096);
    reply_buffer.resize(256);
    rid_base = server.GetResourceIDs().Acquire();
}

XClient::~XClient() {
    // Cancel transport I/O and join before the client/server can disappear.
    pendingImport.reset();
    server.SelectRawInput(this, false);
    is_dead = true;
    if (!await_fences.empty()) server.RemoveSyncWaiter();
    server.GetWindowManager().RemoveCompositeClient(this);
    server.GetSelectionManager().RemoveClient(this);
    static_cast<NativeScreen&>(server.GetScreen()).RemoveClient(this);
    server.GetResourceIDs().Recycle(rid_base);
    for (const auto& it : event_windows) {
        it.first->RemoveEventListener(this);
    }
    event_windows.clear();
    FreeOwnerResources();
    close(socket_fd);
}

void XClient::HandleRequest() {
    if (pendingImport && pendingImport->done.load(std::memory_order_acquire)) FinishDRI3Import();
    // Bound each turn under XServer::Lock. BufferedInputStream may already own
    // the next request, so explicitly reschedule it even without socket EPOLLIN.
    requestsPending = false;
    size_t handled{};
    auto& screen = static_cast<NativeScreen&>(server.GetScreen());
    const bool trace = screen.DiagnosticsEnabled();
    using Clock = std::chrono::steady_clock;
    while (handled < 64 && !is_dead) {
        const auto readStart = trace ? Clock::now() : Clock::time_point{};
        const bool ready = ReadRequestBuffer();
        if (trace) screen.RecordInputRead(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - readStart).count());
        if (!ready) break;
        const auto dispatchStart = trace ? Clock::now() : Clock::time_point{};
        if (!authenticated) {
            ProcAuthenticateRequest();
        } else {
            try {
                ProcNormalRequest();
            } catch (const XRequestError& e) {
                const xReq* req = GetRequestData<xReq>();
                const auto opcode = req->reqType;
                static_cast<NativeScreen&>(server.GetScreen()).ProtocolError(socket_fd, opcode, req->data, e.code, e.data);
                xError error{
                        .type = X_Error,
                        .errorCode = e.code,
                        .sequenceNumber = sequence,
                        .resourceID = e.data,
                        .minorCode = static_cast<CARD16>(opcode >= 128 ? req->data : 0),
                        .majorCode = opcode,
                };
                WriteImmediately(&error, sizeof(error));
            }
        }
        if (trace) screen.RecordRequest(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - dispatchStart).count(), request_size);
        ConsumeRequestBuffer();
        ++handled;
    }
    requestsPending = handled == 64 && !is_dead && !IsWaiting();
    const bool suspended = IsWaiting();
    if (inputSuspended != suspended) {
        inputSuspended = suspended;
        // Level-triggered EPOLLIN would otherwise spin on unread requests
        // queued behind AwaitFence. Completion resumes this client explicitly.
        server.SetWritable(socket_fd, outputWritable);
    }
}

void XClient::SendEvent(xEvent& event) {
    event.u.u.sequenceNumber = sequence;
    WriteImmediately(&event, sizeof(event));
}

ssize_t XClient::Read(void* data, size_t size) { return input_stream.Read(data, size); }

void XClient::Write(const void* data, size_t size) {
    const size_t pos = reply_size;
    reply_size += size;
    if (reply_buffer.size() < reply_size) {
        reply_buffer.resize(base::AlignUp(reply_size, 256));
    }
    memcpy(reply_buffer.data() + pos, data, size);
}

void XClient::WriteImmediately(const void* data, size_t size) {
    if (is_dead || size == 0) return;
    if (pending_output.size() - output_offset + size > 16 * 1024 * 1024) {
        DisconnectOutput(); return;
    }
    const auto* bytes = static_cast<const u8*>(data);
    if (pending_output.empty()) {
        // Most replies and Present acknowledgements fit immediately. Avoid a
        // staging-vector copy and flush bookkeeping on this common path.
        ssize_t n;
        do { n = send(socket_fd, bytes, size, MSG_NOSIGNAL | MSG_DONTWAIT); }
        while (n < 0 && errno == EINTR);
        if (n == ssize_t(size)) return;
        if (n > 0) { bytes += n; size -= n; }
        else if (n == 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) {
            DisconnectOutput(); return;
        }
    }
    pending_output.insert(pending_output.end(), bytes, bytes + size);
    auto& screen = static_cast<NativeScreen&>(server.GetScreen());
    if (screen.DiagnosticsEnabled()) screen.RecordOutputBackpressure(pending_output.size() - output_offset);
    // Queue only the unsent tail after a partial write. Already-blocked sockets
    // are flushed by EPOLLOUT instead of retrying send for every new packet.
    if (!outputWritable) {
        server.SetWritable(socket_fd, true);
        outputWritable = true;
    }
}

void XClient::FlushOutput() {
    if (is_dead) return;
    while (output_offset < pending_output.size()) {
        ssize_t n = send(socket_fd, pending_output.data() + output_offset,
                         pending_output.size() - output_offset, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n > 0) { output_offset += n; continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (!outputWritable) {
                server.SetWritable(socket_fd, true);
                outputWritable = true;
            }
            return;
        }
        // Events are also sent by Android input and scene updates for OTHER
        // clients. Do not unwind their callbacks or throw across JNI on EPIPE.
        DisconnectOutput(); return;
    }
    pending_output.clear(); output_offset = 0;
    if (outputWritable) {
        server.SetWritable(socket_fd, false);
        outputWritable = false;
    }
}

void XClient::DisconnectOutput() {
    is_dead = true;
    pending_output.clear(); output_offset = 0;
    // Defer deletion until epoll handles HUP. Current event fan-outs can still
    // hold pointers into this client's resource/listener tree.
    shutdown(socket_fd, SHUT_RDWR);
}

void XClient::WritePad(size_t size) {
    static std::vector<u8> zero(64);
    if (size != 0) {
        if (zero.size() < size) {
            zero.resize(base::AlignUp(size, 64));
        }
        Write(zero.data(), size);
    }
}

void XClient::Flush() {
    WriteImmediately(reply_buffer.data(), reply_size);
    reply_size = 0;
}

void XClient::AddResource(const sp<XResource>& resource) {
    ASSERT(std::ranges::find(owner_resources, resource) == owner_resources.end());
    owner_resources.push_back(resource);
    server.AddResource(resource);
}

XResource* XClient::GetResource(u32 id) const { return server.GetResource(id); }
sp<XResource> XClient::ShareResource(u32 id) const { return server.ShareResource(id); }

Drawable* XClient::GetDrawable(u32 id) const {
    const auto res = GetResource(id);
    if (res && res->IsDrawable()) {
        return res->AsDrawable();
    }
    return nullptr;
}

void XClient::RemoveResource(u32 id) {
    server.RemoveResource(id);
    owner_resources.remove_if([id](const auto& r) { return r->id == id; });
}

void XClient::FreeOwnerResources() {
    const auto resources = owner_resources;
    for (const auto& resource : resources) {
        if (server.GetResource(resource->id) == resource.get()) {
            resource->OnOwnerDies(this);
            server.RemoveResource(resource->id);
        }
    }
    owner_resources.clear();
}

void XClient::CheckResourceID(const u32 id) const {
    if (!server.GetResourceIDs().IsInInterval(id, rid_base)) {
        throw BadIdChoiceError(id);
    }
}

void XClient::ConsumeRequestBuffer() { request_size = 0; }

bool XClient::ReadRequestBuffer() {
    if (pendingImport) return false;
    if (!await_fences.empty()) {
        auto& screen = static_cast<NativeScreen&>(server.GetScreen());
        bool any = std::ranges::any_of(await_fences, [&](u32 id) { return screen.FenceTriggered(id); });
        if (!any) return false;
        await_fences.clear();
        server.RemoveSyncWaiter();
    }
    auto fill = [this](size_t size) {
        if (size > 16 * 1024 * 1024 || size < request_size)
            throw std::runtime_error("Invalid X11 request length");
        if (request_buffer.size() < size) request_buffer.resize(size);
        while (request_size < size) {
            ssize_t n = Read(request_buffer.data() + request_size, size - request_size);
            if (n <= 0) return false;
            request_size += n;
        }
        return true;
    };
    if (!authenticated) {
        if (request_size < sizeof(xConnClientPrefix) && !fill(sizeof(xConnClientPrefix))) return false;
        auto p = *GetRequestData<xConnClientPrefix>();
        return fill(sizeof(p) + base::AlignUp(p.nbytesAuthProto, 4) + base::AlignUp(p.nbytesAuthString, 4));
    }
    if (request_size < sizeof(xReq) && !fill(sizeof(xReq))) return false;
    size_t length = size_t(GetRequestData<xReq>()->length) * 4;
    if (!length) {
        if (!big_request) throw std::runtime_error("BIG-REQUESTS was not enabled");
        if (request_size < sizeof(xBigReq) && !fill(sizeof(xBigReq))) return false;
        length = size_t(GetRequestData<xBigReq>()->length) * 4;
        if (length < sizeof(xBigReq)) throw std::runtime_error("Invalid big request length");
    }
    if (length < sizeof(xReq)) throw std::runtime_error("Invalid request length");
    if (!fill(length)) return false;
    if (GetRequestData<xReq>()->length == 0) {
        memmove(request_buffer.data() + 4, request_buffer.data() + 8, request_size - 8);
        request_size -= 4;
        GetRequestData<xReq>()->length = std::min<size_t>(65535, request_size / 4);
    }
    return true;
}

} // namespace xorg


