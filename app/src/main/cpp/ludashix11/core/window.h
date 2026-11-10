#pragma once

#include <list>
#include <atomic>
#include <X11/X.h>
#include <X11/Xproto.h>
#include "base/2d.h"
#include "core/property.h"
#include "core/xresource.h"

#define VisibilityNotViewable 3

namespace xorg {

class XClient;
class Drawable;
class Cursor;
class Window;
class WindowManager;

struct WindowAttributes {
    u32 win_gravity{CenterGravity};
    u32 backing_store{NotUseful};
    bool mapped{};
    bool save_under{};
    bool override_redirect{};
    u32 back_pixel{};
    u32 backing_pixel{};
    u32 backing_planes{1};
    u32 bit_gravity{CenterGravity};
    u32 event_mask{};
    u16 dont_propagate_mask{};
    sp<Cursor> cursor;

    bool Update(Window* window, u32 mask, const u32* values, XClient* client);
};

class Window final : public XResource {
public:
    inline static std::atomic<u64> nextGeneration{1};
    const u64 generation{nextGeneration.fetch_add(1, std::memory_order_relaxed)};
    Window(WindowManager& window_manager,
           u32 id,
           Window* parent,
           bool is_input_only,
           s32 x,
           s32 y,
           u16 width,
           u16 height,
           u8 depth,
           s32 border_width,
           XClient* client);

    ~Window() override;

    Cursor* GetCursor() const {
        if (attributes.cursor) {
            return attributes.cursor.get();
        }
        return parent ? parent->GetCursor() : nullptr;
    }

    XClient* GetClient() const { return owner_client; }

    bool IsRoot() const { return parent == nullptr; }

    bool IsInputOnly() const { return is_input_only; }

    bool IsMapped() const { return attributes.mapped; }
    bool IsCompositeRedirected() const { return composite_redirected; }

    Drawable* GetDrawable() const { return drawable.get(); }
    const sp<Drawable>& SharedDrawable() const { return drawable; }

    const std::list<Window*>& GetChildren() const { return children; }

    u16 GetBorderWidth() const { return border_width; }

    const Rect& GetOutRect() const { return out_rect; }

    const Rect& GetInRect() const { return in_rect; }

    Window* GetParent() const { return parent; }

    u32 GetWidth() const { return width; }

    u32 GetHeight() const { return height; }

    u8 GetDepth() const { return depth; }

    WindowAttributes& GetAttributes() { return attributes; }

    bool IsAllAncestorsMapped() const;

    bool IsAncestorOf(const Window* w) const;

    bool IsInferiorOf(const Window* w) const;

    Property* GetProperty(u32 atom) const;

    Property* AddProperty(u32 atom, u32 type, u8 format, u8 mode, const std::vector<u8>& data);

    bool ModifyProperty(u32 atom, u32 type, u8 format, u8 mode, const std::vector<u8>& data);

    void DeleteProperty(u32 atom);

    template <typename Callback> void ForeachEventListeners(u32 event_id, Callback&& callback) {
        constexpr bool BOOL_BREAK = std::is_same_v<bool, std::invoke_result_t<Callback, XClient*>>;
        for (const auto& it : client_masks) {
            if (it.second & event_id) {
                if constexpr (BOOL_BREAK) {
                    if (callback(it.first)) {
                        break;
                    }
                } else {
                    callback(it.first);
                }
            }
        }
    }

    bool HasEventListenerFor(u32 event_id) const {
        for (const auto& it : client_masks) {
            if (it.second & event_id) {
                return true;
            }
        }
        return false;
    }

    u32 GetAllEventMasks() const {
        u32 mask = 0;
        for (const auto& it : client_masks) {
            mask |= it.second;
        }
        return mask;
    }
    u32 GetEventMask(XClient* client) const {
        auto i = client_masks.find(client);
        return i == client_masks.end() ? 0 : i->second;
    }

    void PutClientMask(XClient* client, u32 mask) { client_masks[client] = mask; }

    void RemoveEventListener(XClient* client) { client_masks.erase(client); }

    void Move(s32 dx, s32 dy, s32 dw, s32 dh);

    void Reparent(XClient* caller_client, Window* new_parent, s32 x, s32 y);

    void MakeExposure();

    bool Occuldes(Window* w1, Window* w2) const;

    void UpdateVisibility();

    void UpdateAffectedVisibility();

    void FocusOutNotify(u32 detail, u32 mode);

    void FocusInNotify(u32 detail, u32 mode);

    void LeaveEnterNotify(s32 x, s32 y, Window* enter_window, u32 mode);

    void PointerNotify(u32 type, s32 x, s32 y, u32 detail, Window* window, u32 mode);

    void PointerMotionNotify(s32 x, s32 y, u32 button_mask, XClient* grab_client);

    Window* ButtonNotify(bool down, s32 x, s32 y, u32 button, XClient* grab_client, XClient** recipient = nullptr);

    bool KeyNotify(bool down, s32 x, s32 y, u32 keycode, XClient* grab_client);

    void GrabButtonNotify(bool down,
                          s32 x,
                          s32 y,
                          u32 button,
                          XClient* grab_client,
                          u32 event_mask,
                          bool owner_events);

    void GrabKeyNotify(bool down, s32 x, s32 y, u32 keycode, XClient* grab_client, bool owner_events);

    void GrabPointerMoveNotify(
            s32 x, s32 y, u32 button_mask, XClient* grab_client, u32 event_mask, bool owner_events);

    Window* WindowAtPoint(s16 x, s16 y);

    u32 CalculateVisibility() const;

    void RecreateDrawable();

    void OnOwnerDies(XClient* owner) override;

private:
    friend class WindowManager;
    bool RouteGrabEvent(xEvent& event, u32 mask, XClient* client);

    WindowManager& window_manager;
    sp<Drawable> drawable{};

    bool is_input_only{};
    bool composite_redirected{};
    Rect out_rect{};
    Rect in_rect{};
    u16 border_width{};
    u32 visibility{VisibilityNotViewable};
    bool exposed{};
    Window* parent{};
    XClient* owner_client{};
    u16 width{};
    u16 height{};
    u8 depth{};
    WindowAttributes attributes{};
    std::unordered_map<XClient*, u32> client_masks;
    std::list<Window*> children;
    std::unordered_map<u32, sp<Property>> properties;
};

}  // namespace xorg
