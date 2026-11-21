#pragma once

#include <list>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <thread>
#include <atomic>
#include <android/hardware_buffer.h>
#include "base/owned_descriptor.h"
#include <X11/Xproto.h>
#include "core/request_error.h"
#include "core/wire_packets.h"
#include "core/protocol_object.h"
#include "io/request_stream.h"

namespace ludashi::x11 {

class DisplayRuntime;
class ProtocolObject;
class NativeDrawable;
class WindowNode;
class OffscreenImage;
class PointerSprite;
class PaintState;

class ClientSession {
public:
    explicit ClientSession(DisplayRuntime& server, OwnedDescriptor connection);

    ~ClientSession();

    DisplayRuntime* GetServer() const { return &server; }

    void ServiceInput();
    void FlushOutput();
    bool IsWaiting() const { return pendingImport || !await_fences.empty(); }
    bool HasPendingRequests() const { return requestsPending; }
    bool IsInputSuspended() const { return inputSuspended; }
    void SendPacket(const void* data, size_t size) { WriteImmediately(data, size); }
    u16 Sequence() const { return sequence; }
    int Socket() const { return connection.Get(); }

    void DeliverEvent(const xEvent& event);
    void DeliverEvent(CorePacket packet);
    void SendXIRawEvent(u16 type, u32 detail, s32 dx = 0, s32 dy = 0);

    ProtocolObject* GetResource(u32 id) const;
    sp<ProtocolObject> ShareResource(u32 id) const;

    NativeDrawable* GetDrawable(u32 id) const;
    const sp<NativeDrawable>* FindDrawingStorage(u32 id) const;
    // DRI3 clients may address a GLXWindow wrapper rather than its core X window.
    NativeDrawable* ResolveGLXDrawable(u32 id) const;

    template <typename T> T* GetResourceAs(u32 id) const {
        const auto res = GetResource(id);
        if (res != nullptr) {
            if constexpr (std::is_same_v<T, WindowNode>) { if (res->GetType() != ObjectKind::WindowNode) return nullptr; }
            else if constexpr (std::is_same_v<T, OffscreenImage>) { if (res->GetType() != ObjectKind::OffscreenImage) return nullptr; }
            else if constexpr (std::is_same_v<T, PaintState>) { if (res->GetType() != ObjectKind::GC) return nullptr; }
            else if constexpr (std::is_same_v<T, PointerSprite>) { if (res->GetType() != ObjectKind::PointerSprite) return nullptr; }
            return res->As<T>();
        }
        return nullptr;
    }

    void AddResource(const sp<ProtocolObject>& resource);

    void RemoveResource(u32 id);

    void TrackEventWindow(WindowNode* window) { event_windows.insert(window); }

    void RemoveEventWindow(WindowNode* window) { event_windows.erase(window); }

private:
    friend class WireExtensions;
    void ProcGLX();
    void ProcComposite();
    void ProcRender();
    void ProcDRI3();
    void FinishDRI3Import();
    struct BufferImport {
        OwnedDescriptor transfer;
        std::thread worker;
        std::atomic<bool> done{false};
        AHardwareBuffer* buffer{};
        u32 pixmap{};
        u16 width{}, height{}, sequence{};
        u8 opcode{}, minor{};
        ~BufferImport();
    };
    std::unique_ptr<BufferImport> pendingImport;
    void ProcPresent();
    void ProcSync();
    void ProcRandR();
    void ProcXInput();
    std::unordered_map<u32, std::unordered_map<u16, u32>> xiSelections;
    u32 xiRawMask{};
    u8 xiOpcode{};
    std::vector<u8> pending_output;
    size_t output_offset{};
    bool outputWritable{};
    bool batchReplies{};
    bool inputSuspended{};
    DisplayRuntime& server;
    OwnedDescriptor connection;
    bool is_dead{};
    bool established{};
    bool big_request{};
    bool requestsPending{};
    u16 sequence{};
    u32 rid_base{};
    RequestStream requestStream;
    std::unordered_set<u32> shmSegments;
    std::vector<u32> await_fences;
    friend class NativeScreen;

    std::span<u8> request_buffer;
    size_t request_size{};
    std::vector<u8> reply_buffer;
    std::unordered_map<u32, sp<ProtocolObject>> owner_resources;
    std::unordered_set<WindowNode*> event_windows;

    template <typename T> T* GetRequestData() {
        static_assert(alignof(T) <= 4);
        RequireRequestHeader<T>();
        return reinterpret_cast<T*>(request_buffer.data());
    }

    template<class Request> void RequireRequestHeader() const {
        if (request_size < sizeof(Request)) throw BadLengthError();
    }

    template<class Request> void RequireExactRequest() const {
        if (request_size != sizeof(Request)) throw BadLengthError();
    }

    int ConsumeRequestFd() { return requestStream.TakeDescriptor(); }

    void Write(const void* data, size_t size);

    void WriteImmediately(const void* data, size_t size);
    void DisconnectOutput();

    void WritePad(size_t size);

    void Flush();

    void FreeOwnerResources();

    void CheckResourceID(u32 id) const;

    bool CompleteSetup(bool trace);
    void SendSetupReply();
    bool DispatchBatch(unsigned budget, bool trace);
    bool CommandStreamReady();
    void StageFrame(std::span<u8> frame);
    void RouteCommand(u8 opcode);
    void ReplyWithError(u16 serial, u8 opcode, u16 minor, const XRequestError& error);


    void ProcBigRequest();
    void ProcShmRequest();

    void ApplyPaintCommand(u8 opcode);
    void ApplyImageResourceCommand(u8 opcode);
    void ApplyWindowCommand(u8 opcode);
    void ApplyDisplayPolicyCommand(u8 opcode);
    void ApplyKeyboardCommand(u8 opcode);
    void ApplyPointerCommand(u8 opcode);
    void ApplyRasterCommand(u8 opcode);
    void ApplyPropertyCommand(u8 opcode);
    void ApplySelectionCommand(u8 opcode);
    void ApplyCatalogCommand(u8 opcode);
    void ApplyCompatibilityCommand(u8 opcode);

    void UploadImage(u8);
    void DownloadImage(u8);
};

}  // namespace ludashi::x11

