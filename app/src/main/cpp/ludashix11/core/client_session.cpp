#include <unistd.h>
#include <X11/Xproto.h>
#include "core/request_error.h"
#include "core/window_node.h"
#include "core/offscreen_image.h"
#include "core/window_tree.h"
#include "core/client_session.h"
#include "native_screen.h"
#include "core/sysv_segments.h"
#include <cerrno>
#include <sys/socket.h>
#include "core/protocol_object.h"
#include "core/display_runtime.h"

namespace ludashi::x11 {

ClientSession::ClientSession(DisplayRuntime& server, OwnedDescriptor socket)
    : server(server), connection(std::move(socket)), requestStream(connection.Get()) {
    reply_buffer.reserve(256);
    rid_base = server.GetResourceIDs().Acquire();
}

ClientSession::~ClientSession() {
    // Cancel transport I/O and join before the client/server can disappear.
    pendingImport.reset();
    server.SelectRawInput(this, false);
    is_dead = true;
    for (u32 segment : shmSegments) server.Segments().Detach(segment);
    if (!await_fences.empty()) server.RemoveSyncWaiter();
    server.Windows().RemoveCompositeClient(this);
    server.Selections().Release(this);
    server.GetScreen().RemoveClient(this);
    server.GetResourceIDs().Recycle(rid_base);
    for (auto* window : event_windows) window->Unsubscribe(this);
    event_windows.clear();
    server.Windows().ReleaseClientGrabs(this);
    FreeOwnerResources();
}

namespace {

using Clock = std::chrono::steady_clock;

u64 ElapsedNanoseconds(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
}

} // namespace

void ClientSession::StageFrame(std::span<u8> frame) {
    request_buffer = frame;
    request_size = frame.size();
    // Reply to a single command immediately. Buffer already-pipelined replies.
    batchReplies = requestStream.HasFollowingBytes();
}

bool ClientSession::CompleteSetup(bool trace) {
    const auto start = trace ? Clock::now() : Clock::time_point{};
    const auto frame = requestStream.ReadSetup();
    auto& screen = server.GetScreen();
    if (trace) screen.RecordInputRead(ElapsedNanoseconds(start));
    if (frame.empty()) return false;

    StageFrame(frame);
    const auto replyStart = trace ? Clock::now() : Clock::time_point{};
    SendSetupReply();
    if (trace) screen.RecordRequest(ElapsedNanoseconds(replyStart), frame.size());
    requestStream.Consume();
    established = true;
    return true;
}

bool ClientSession::DispatchBatch(unsigned budget, bool trace) {
    auto& screen = server.GetScreen();
    while (budget && !is_dead) {
        const auto readStart = trace ? Clock::now() : Clock::time_point{};
        auto frame = CommandStreamReady() ? requestStream.ReadCommand(big_request) : std::span<u8>{};
        if (trace) screen.RecordInputRead(ElapsedNanoseconds(readStart));
        if (frame.empty()) return false;

        StageFrame(frame);
        const u8 opcode = frame[0];
        const u8 detail = frame[1];
        const auto start = trace ? Clock::now() : Clock::time_point{};
        ++sequence;
        try {
            RouteCommand(opcode);
        } catch (const XRequestError& error) {
            reply_buffer.clear();
            ReplyWithError(sequence, opcode, opcode < 128 ? 0 : detail, error);
        }
        if (trace) screen.RecordRequest(ElapsedNanoseconds(start), frame.size());
        requestStream.Consume();
        --budget;
    }
    return budget == 0;
}

void ClientSession::ServiceInput() {
    if (pendingImport && pendingImport->done.load(std::memory_order_acquire)) FinishDRI3Import();

    constexpr unsigned commandsPerTurn = 64;
    const bool trace = server.GetScreen().DiagnosticsEnabled();
    unsigned budget = commandsPerTurn;
    if (!established && !is_dead && CompleteSetup(trace)) --budget;
    const bool usedBudget = established && DispatchBatch(budget, trace);

    batchReplies = false;
    if (!outputWritable) FlushOutput();
    const bool suspended = IsWaiting();
    requestsPending = usedBudget && !is_dead && !suspended;
    if (inputSuspended != suspended) {
        inputSuspended = suspended;
        // A phase waiting for a fence or import must not spin on EPOLLIN.
        server.SetWritable(Socket(), outputWritable);
    }
}

void ClientSession::ReplyWithError(u16 serial, u8 opcode, u16 minor, const XRequestError& error) {
    server.GetScreen().ProtocolError(Socket(), opcode, static_cast<u8>(minor), error.code, error.data);
    CorePacket packet{};
    packet[1] = error.code;
    PutWire16(packet, 2, serial);
    PutWire32(packet, 4, error.data);
    PutWire16(packet, 8, minor);
    packet[10] = opcode;
    SendPacket(packet.data(), packet.size());
}

void ClientSession::DeliverEvent(const xEvent& event) {
    DeliverEvent(std::bit_cast<CorePacket>(event));
}

void ClientSession::DeliverEvent(CorePacket packet) {
    // KeymapNotify uses the 31 bytes after its type for the key bitmap.
    if ((packet[0] & 0x7f) != KeymapNotify) PutWire16(packet, 2, sequence);
    SendPacket(packet.data(), packet.size());
}

void ClientSession::Write(const void* data, size_t size) {
    if (!size) return;
    if (size > 16 * 1024 * 1024 - reply_buffer.size()) throw BadAllocError();
    const auto* bytes = static_cast<const u8*>(data);
    reply_buffer.insert(reply_buffer.end(), bytes, bytes + size);
}

void ClientSession::WriteImmediately(const void* data, size_t size) {
    if (is_dead || size == 0) return;
    if (pending_output.size() - output_offset + size > 16 * 1024 * 1024) {
        DisconnectOutput(); return;
    }
    const auto* bytes = static_cast<const u8*>(data);
    if (!batchReplies && pending_output.empty()) {
        // Most replies and Present acknowledgements fit immediately. Avoid a
        // staging-vector copy and flush bookkeeping on this common path.
        ssize_t n;
        do { n = send(Socket(), bytes, size, MSG_NOSIGNAL | MSG_DONTWAIT); }
        while (n < 0 && errno == EINTR);
        if (n == ssize_t(size)) return;
        if (n > 0) { bytes += n; size -= n; }
        else if (n == 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) {
            DisconnectOutput(); return;
        }
    }
    if (output_offset != 0 && output_offset >= pending_output.size() - output_offset) {
        pending_output.erase(pending_output.begin(), pending_output.begin() + output_offset);
        output_offset = 0;
    }
    pending_output.insert(pending_output.end(), bytes, bytes + size);
    constexpr size_t batchLimit = 64 * 1024;
    if (!outputWritable && (!batchReplies || pending_output.size() - output_offset >= batchLimit))
        FlushOutput();
}

void ClientSession::FlushOutput() {
    if (is_dead) return;
    while (output_offset < pending_output.size()) {
        ssize_t n = send(Socket(), pending_output.data() + output_offset,
                         pending_output.size() - output_offset, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n > 0) { output_offset += n; continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            auto& screen = server.GetScreen();
            if (screen.DiagnosticsEnabled()) screen.RecordOutputBackpressure(pending_output.size() - output_offset);
            if (!outputWritable) {
                server.SetWritable(Socket(), true);
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
        server.SetWritable(Socket(), false);
        outputWritable = false;
    }
}

void ClientSession::DisconnectOutput() {
    is_dead = true;
    pending_output.clear(); output_offset = 0;
    // Defer deletion until epoll handles HUP. Current event fan-outs can still
    // hold pointers into this client's resource/listener tree.
    shutdown(Socket(), SHUT_RDWR);
}

void ClientSession::WritePad(size_t size) {
    if (size > 16 * 1024 * 1024 - reply_buffer.size()) throw BadAllocError();
    reply_buffer.insert(reply_buffer.end(), size, 0);
}

void ClientSession::Flush() {
    WriteImmediately(reply_buffer.data(), reply_buffer.size());
    reply_buffer.clear();
}

void ClientSession::AddResource(const sp<ProtocolObject>& resource) {
    CheckResourceID(resource->id);
    if (GetResource(resource->id) ||
        (shmSegments.contains(resource->id) && server.Segments().Get(resource->id)))
        throw BadIdChoiceError(resource->id);
    const auto [entry, inserted] = owner_resources.emplace(resource->id, resource);
    if (!inserted) throw BadIdChoiceError(resource->id);
    try {
        server.AddResource(resource);
    } catch (...) {
        owner_resources.erase(entry);
        throw;
    }
}

ProtocolObject* ClientSession::GetResource(u32 id) const {
    return server.GetResource(id);
}

sp<ProtocolObject> ClientSession::ShareResource(u32 id) const {
    return server.ShareResource(id);
}

const sp<NativeDrawable>* ClientSession::FindDrawingStorage(u32 id) const {
    auto* object = server.GetResource(id);
    if (!object) return nullptr;
    if (object->GetType() == ObjectKind::OffscreenImage) return &object->As<OffscreenImage>()->Image();
    if (object->GetType() == ObjectKind::WindowNode) return &object->As<WindowNode>()->SharedDrawable();
    return nullptr;
}

NativeDrawable* ClientSession::GetDrawable(u32 id) const {
    const auto* storage = FindDrawingStorage(id);
    return storage ? storage->get() : nullptr;
}

void ClientSession::RemoveResource(u32 id) {
    server.RemoveResource(id);
    owner_resources.erase(id);
}

void ClientSession::FreeOwnerResources() {
    // Detach first because destroying a parent can remove owned children.
    auto owned = std::move(owner_resources);
    for (const auto& [id, resource] : owned) {
        if (server.GetResource(id) != resource.get()) continue;
        if (resource->GetType() == ObjectKind::WindowNode)
            resource->As<WindowNode>()->RemoveWithOwner(this);
        server.RemoveResource(id);
    }
}

void ClientSession::CheckResourceID(u32 id) const {
    if (!server.GetResourceIDs().IsInInterval(id, rid_base)) throw BadIdChoiceError(id);
}

bool ClientSession::CommandStreamReady() {
    if (pendingImport) return false;
    if (!await_fences.empty()) {
        auto& screen = server.GetScreen();
        bool any = std::ranges::any_of(await_fences, [&](u32 id) { return screen.FenceTriggered(id); });
        if (!any) return false;
        await_fences.clear();
        server.RemoveSyncWaiter();
    }
    return true;
}

} // namespace ludashi::x11


