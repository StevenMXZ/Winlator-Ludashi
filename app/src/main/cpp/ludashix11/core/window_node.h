#pragma once

#include <list>
#include <atomic>
#include <X11/X.h>
#include <X11/Xproto.h>
#include "base/rectangle.h"
#include "core/window_metadata.h"
#include "core/protocol_object.h"

#define VisibilityNotViewable 3

namespace ludashi::x11 {

class ClientSession;
class NativeDrawable;
class PointerSprite;
class WindowNode;
class WindowTree;

struct WindowOptions {
    struct Backing {
        u32 fallbackPixel{};
        u32 planeMask{1};
        u32 policy{NotUseful};
        bool saveUnder{};
    } backing;
    struct Resize {
        u32 pixelGravity{CenterGravity};
        u32 childGravity{NorthWestGravity};
    } resize;
    u32 background{};
    u16 propagationStop{};
    bool bypassManager{};
};

class WindowNode final : public ProtocolObject {
public:
    inline static std::atomic<u64> nextGeneration{1};
    const u64 generation{nextGeneration.fetch_add(1, std::memory_order_relaxed)};
    struct Definition {
        WindowNode* parent{};
        ClientSession* owner{};
        s32 x{};
        s32 y{};
        u16 width{};
        u16 height{};
        u16 border{};
        u8 depth{};
        bool inputOnly{};
    };
    WindowNode(WindowTree& tree, u32 id, const Definition& initial);

    ~WindowNode() override;

    PointerSprite* GetCursor() const {
        if (pointerOverride) {
            return pointerOverride.get();
        }
        return parent ? parent->GetCursor() : nullptr;
    }

    ClientSession* GetClient() const { return owner_client; }

    bool IsRoot() const { return parent == nullptr; }

    bool IsInputOnly() const { return is_input_only; }

    bool IsMapped() const { return mapped; }
    bool IsCompositeRedirected() const { return composite_redirected; }

    NativeDrawable* GetDrawable() const { return drawable.get(); }
    const sp<NativeDrawable>& SharedDrawable() const { return drawable; }

    const std::list<WindowNode*>& GetChildren() const { return children; }

    u16 GetBorderWidth() const { return placement.border; }

    Rect FrameBounds() const { return placement.Frame(); }

    Rect ContentBounds() const { return placement.Content(); }

    WindowNode* GetParent() const { return parent; }

    u32 GetWidth() const { return placement.width; }

    u32 GetHeight() const { return placement.height; }

    u8 GetDepth() const { return depth; }

    const WindowOptions& Options() const { return options; }
#ifdef LUDASHI_HOST_TEST
    void SetTestMapping(bool value) { mapped = value; }
#endif

    bool IsAllAncestorsMapped() const;

    bool IsAncestorOf(const WindowNode* w) const;

    bool IsInferiorOf(const WindowNode* w) const;

    const WindowMetadata* GetProperty(u32 atom) const;

    bool ModifyProperty(u32 atom, u32 type, u8 format, u8 mode, std::span<const u8> data);

    void DeleteProperty(u32 atom);

    template <typename Visitor>
    void DispatchToSubscribers(u32 mask, Visitor&& visit) {
        if (!HasSubscribers(mask)) return;
        for (auto entry = subscriptions.rbegin(); entry != subscriptions.rend(); ++entry) {
            if (!(entry->mask & mask)) continue;
            if constexpr (std::is_same_v<bool, std::invoke_result_t<Visitor, ClientSession*>>) {
                if (visit(entry->client)) return;
            } else {
                visit(entry->client);
            }
        }
    }

    bool HasSubscribers(u32 mask) const { return (selectedEvents & mask) != 0; }
    u32 SelectedEvents() const { return selectedEvents; }
    u32 InterestOf(const ClientSession* client) const;
    void Subscribe(ClientSession* client, u32 mask);
    void Unsubscribe(const ClientSession* client);

    void Move(s32 dx, s32 dy, s32 dw, s32 dh);

    void Reparent(ClientSession* caller_client, WindowNode* new_parent, s32 x, s32 y);

    void MakeExposure();

    bool HasStackOverlap(const WindowNode* upper, const WindowNode* lower) const;

    void UpdateVisibility();

    void UpdateAffectedVisibility();

    void FocusOutNotify(u32 detail, u32 mode);

    void FocusInNotify(u32 detail, u32 mode);

    void LeaveEnterNotify(s32 x, s32 y, WindowNode* enter_window, u32 mode);

    void PointerNotify(u32 type, s32 x, s32 y, u32 detail, WindowNode* window, u32 mode);

    void PointerMotionNotify(s32 x, s32 y, u32 button_mask, ClientSession* grab_client);

    WindowNode* ButtonNotify(bool down, s32 x, s32 y, u32 button, ClientSession* grab_client, ClientSession** recipient = nullptr);

    bool KeyNotify(bool down, s32 x, s32 y, u32 keycode, ClientSession* grab_client,
                   const WindowNode* focusBoundary = nullptr);

    bool GrabButtonNotify(bool down,
                          s32 x,
                          s32 y,
                          u32 button,
                          ClientSession* grab_client,
                          u32 event_mask,
                          bool owner_events);

    void GrabKeyNotify(bool down, s32 x, s32 y, u32 keycode, ClientSession* client,
                       WindowNode* normalTarget, const WindowNode* focusBoundary);

    void GrabPointerMoveNotify(
            s32 x, s32 y, u32 button_mask, ClientSession* grab_client, u32 event_mask, bool owner_events);

    WindowNode* WindowAtPoint(s16 x, s16 y);

    u32 CalculateVisibility() const;

    void RecreateDrawable();

    void RemoveWithOwner(ClientSession* owner);

private:
    friend class WindowTree;
    bool ApplyOptions(ClientSession* client, u32 mask, const u32* values);
    void NotifyRelocation(WindowNode& previous, s16 x, s16 y);
    void Translate(s32 dx, s32 dy);
    void RefreshSelectedEvents();
    void DisconnectSubscribers();
    void NotifyProperty(u32 atom, u8 state);
    xEvent InputEvent(u8 type, u8 detail, s32 x, s32 y, u32 state, const WindowNode* child = nullptr) const;
    WindowNode* FindEventWindow(u32 mask, WindowNode*& child, const WindowNode* boundary = nullptr);
    WindowNode* DeliverInput(u8 type, u8 detail, s32 x, s32 y, u32 state, u32 mask,
                         ClientSession* excluded, ClientSession** recipient = nullptr, const WindowNode* boundary = nullptr);
    bool RouteGrabEvent(xEvent& event, u32 mask, ClientSession* client, const WindowNode* boundary = nullptr);

    WindowTree& window_manager;
    sp<NativeDrawable> drawable{};

    bool is_input_only{};
    bool composite_redirected{};
    struct Placement {
        s32 x, y;
        u16 width, height, border;

        Rect Frame() const {
            return {x, y, x + width + 2 * border, y + height + 2 * border};
        }

        Rect Content() const {
            const s32 left = x + border, top = y + border;
            return {left, top, left + width, top + height};
        }
    } placement;
    u32 visibility{VisibilityNotViewable};
    bool exposed{};
    WindowNode* parent{};
    ClientSession* owner_client{};
    u8 depth{};
    WindowOptions options{};
    sp<PointerSprite> pointerOverride;
    bool mapped{};
    struct Subscription {
        ClientSession* client;
        u32 mask;
    };
    std::vector<Subscription> subscriptions;
    u32 selectedEvents{};
    std::list<WindowNode*> children;
    // Assigned on insertion, valid across restacks and reparenting.
    std::list<WindowNode*>::iterator stackPosition{};
    std::unordered_map<u32, WindowMetadata> properties;
};

}  // namespace ludashi::x11
