#pragma once

#include <list>
#include <unordered_map>
#include <vector>
#include <X11/Xproto.h>
#include "base/log.h"
#include "core/xresource.h"
#include "io/bufferd_io.h"

namespace xorg {

class XServer;
class XResource;
class Window;
class Pixmap;
class Cursor;
class GraphicsContext;

class XClient {
public:
    explicit XClient(XServer& server, int fd);

    ~XClient();

    XServer* GetServer() const { return &server; }

    void HandleRequest();
    void FlushOutput();
    bool IsWaiting() const { return !await_fences.empty(); }
    bool HasPendingRequests() const { return requestsPending; }
    bool IsInputSuspended() const { return inputSuspended; }
    void SendPacket(const void* data, size_t size) { WriteImmediately(data, size); }
    u16 Sequence() const { return sequence; }
    int Socket() const { return socket_fd; }

    void SendEvent(xEvent& event);
    void SendXIRawEvent(u16 type, u32 detail, s32 dx = 0, s32 dy = 0);

    XResource* GetResource(u32 id) const;
    sp<XResource> ShareResource(u32 id) const;

    Drawable* GetDrawable(u32 id) const;
    // DRI3 clients may address a GLXWindow wrapper rather than its core X window.
    Drawable* ResolveGLXDrawable(u32 id) const;

    template <typename T> T* GetResourceAs(u32 id) const {
        const auto res = GetResource(id);
        if (res != nullptr) {
            if constexpr (std::is_same_v<T, Window>) { if (res->GetType() != XResourceType::Window) return nullptr; }
            else if constexpr (std::is_same_v<T, Pixmap>) { if (res->GetType() != XResourceType::Pixmap) return nullptr; }
            else if constexpr (std::is_same_v<T, GraphicsContext>) { if (res->GetType() != XResourceType::GC) return nullptr; }
            else if constexpr (std::is_same_v<T, Cursor>) { if (res->GetType() != XResourceType::Cursor) return nullptr; }
            return res->As<T>();
        }
        return nullptr;
    }

    void AddResource(const sp<XResource>& resource);

    void RemoveResource(u32 id);

    void PutEventWindow(Window* window, u32 mask) { event_windows[window] = mask; }

    void RemoveEventWindow(Window* window) { event_windows.erase(window); }

private:
    friend class ExtensionManager;
    void ProcGLX();
    void ProcComposite();
    void ProcRender();
    void ProcDRI3();
    void ProcPresent();
    void ProcSync();
    void ProcRandR();
    void ProcCompat();
    void ProcXInput();
    std::unordered_map<u32, std::unordered_map<u16, u32>> xiSelections;
    u32 xiRawMask{};
    u8 xiOpcode{};
    std::vector<u8> pending_output;
    size_t output_offset{};
    bool outputWritable{};
    bool inputSuspended{};
    XServer& server;
    int socket_fd{};
    bool is_dead{};
    bool authenticated{};
    bool big_request{};
    bool requestsPending{};
    u16 sequence{};
    u32 rid_base{};
    BufferedInputStream input_stream;
    std::vector<u32> await_fences;
    friend class NativeScreen;

    std::vector<u8> request_buffer;
    size_t request_size{};
    std::vector<u8> reply_buffer;
    size_t reply_size{};
    std::list<sp<XResource>> owner_resources;
    std::unordered_map<Window*, u32> event_windows;

    template <typename T> T* GetRequestData() {
        return reinterpret_cast<T*>(request_buffer.data());
    }

    int ConsumeRequestFd() { return input_stream.ConsumeFd(); }

    ssize_t Read(void* data, size_t size);

    void Write(const void* data, size_t size);

    void WriteImmediately(const void* data, size_t size);
    void DisconnectOutput();

    void WritePad(size_t size);

    void Flush();

    void FreeOwnerResources();

    void CheckResourceID(u32 id) const;

    bool ReadRequestBuffer();

    void ConsumeRequestBuffer();

    void ProcAuthenticateRequest();

    void ProcNormalRequest();

    void ProcQueryExtension();

    void ProcBigRequest();

    void ProcShmRequest();


    void ProcGetProperty();

    void ProcChangeProperty();

    void ProcDeleteProperty();

    void ProcCreateGC();

    void ProcChangeGC();

    void ProcSetClipRectangles();

    void ProcFreeGC();

    void ProcCreateColormap();

    void ProcFreeColormap();

    void ProcInternAtom();

    void ProcQueryTree();

    void ProcGetKeyboardMapping();

    void ProcGetModifierMapping();

    void ProcQueryPointer();

    void ProcGrabPointer();

    void ProcUngrabPointer();

    void ProcCreatePixmap();

    void ProcFreePixmap();

    void ProcGetInputFocus();

    void ProcOpenFont();

    void ProcListFonts();

    void ProcCreateWindow();

    void ProcChangeWindowAttributes();

    void ProcGetWindowAttributes();

    void ProcConfigureWindow();

    void ProcGetGeometry();

    void ProcReparentWindow();

    void ProcMapWindow();

    void ProcUnmapWindow();

    void ProcDestroyWindow();

    void ProcSetInputFocus();

    void ProcSendEvent();

    void ProcGetSelectionOwner();

    void ProcCreateGlyphCursor();

    void ProcPolyFillRectangle();

    void ProcPolyLine();

    void ProcPolySegment();

    void ProcPutImage();

    void ProcGetImage();

    void ProcCreateCursor();

    void ProcForceScreenSaver();

    void ProcGetScreenSaver();

    void ProcSetScreenSaver();

    void ProcWarpPointer();
};

}  // namespace xorg

