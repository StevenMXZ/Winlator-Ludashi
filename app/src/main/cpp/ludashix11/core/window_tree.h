#pragma once

#include <X11/X.h>
#include <array>
#include <deque>
#include <functional>
#include <optional>
#include <utility>
#include "base/native_types.h"
#include "core/display_dimensions.h"
#include "core/window_node.h"
#include "core/display_runtime.h"

namespace ludashi::x11 {

class DrawableManager;
class WindowNode;
class ClientSession;
class NativeScreen;

class WindowTree final {
public:
    WindowTree(DisplayRuntime& server, DisplayDimensions dimensions);

    DisplayRuntime& GetServer() const { return server; }

    NativeScreen& GetScreen() const;


    WindowNode* GetRootWindow() const { return root_window.get(); }

    WindowNode* GetPointerWindow(s32 x, s32 y) const;
    std::pair<s32, s32> GetPointerPosition() const;

    u8 GetFocusRevertTo() const { return focus_revert_to; }

    WindowNode* FocusedWindow() const { return focus_window; }

    sp<WindowNode> CreateWindow(ClientSession* client,
                            u32 id,
                            WindowNode* parent,
                            s16 x,
                            s16 y,
                            u16 width,
                            u16 height,
                            u16 border_width,
                            u16 win_class,
                            u8 depth,
                            u32 mask,
                            const u32* values);

    bool ConfigureWindow(ClientSession* caller_client, WindowNode* window, u32 mask, const u32* values);
    void RedirectComposite(ClientSession*, WindowNode*, u8 update, bool redirect);
    void RemoveCompositeClient(ClientSession*);
    void RemoveCompositeWindow(WindowNode*);
    void CompositeCopy(WindowNode* source, WindowNode* destination, s16 x, s16 y);

    bool MapWindow(const ClientSession* caller, WindowNode* window, bool publishScene = true);
    std::function<void(WindowNode*)> onWindowMapped;

    bool UnmapWindow(WindowNode* window, bool publishScene = true);

    void FocusInOutNotify(WindowNode* lost, WindowNode* gained, WindowNode* pointer, WindowNode* root, u32 mode,
                          bool lostCanBePointerRoot = true, bool gainedCanBePointerRoot = true) const;

    void RevertFocus(WindowNode* window);

    void DestroyWindow(WindowNode* window, bool release_resource);

    void SetInputFocus(WindowNode* window, u8 revert_to, u32 time);

    void ChangeWindowAttributes(ClientSession* client, WindowNode* window, u32 mask, const u32* values);

    u32 GrabPointer(ClientSession* client,
                    WindowNode* grab_window,
                    WindowNode* confine_window,
                    PointerSprite* cursor,
                    u32 time,
                    u32 event_mask,
                    bool owner_events, u8 pointerMode = GrabModeAsync, u8 keyboardMode = GrabModeAsync);

    void UngrabPointer(const ClientSession* client, u32 time);
    u32 GrabKeyboard(ClientSession* client, WindowNode* window, u32 time, bool ownerEvents,
                     u8 pointerMode, u8 keyboardMode);
    void UngrabKeyboard(const ClientSession* client, u32 time);
    void ReleaseClientGrabs(const ClientSession* client);
    void AllowEvents(const ClientSession* client, u8 mode, u32 time);

    void UpdatePointer(u32 mode);

    PointerSprite* UpdatePointerPosition(s32 x, s32 y, u32 mode, bool notify);

    void OnPointerMove(s32 x, s32 y);

    void OnPointerButtonChange(u16 button, bool down);

    void OnKeyboardKeyChange(u32 keycode, bool down);

    u32 GetButtonMasks() const;
    u32 GetInputTime() const;
    u32 GetInputState() const { return eventState ? *eventState : GetButtonMasks(); }

private:
    friend class KeyState;
    enum Device : u8 { Pointer, Keys };
    enum class InputKind : u8 { Move, Button, Key };
    enum class Freeze : u8 { Running, Frozen, Step };
    struct InputChange {
        InputKind kind;
        s32 x{}, y{};
        u16 code{};
        bool down{};
        bool replay{};
        u32 time{};
        u32 state{};
        u64 order{};
    };
    struct GrabFreeze {
        std::array<Freeze, 2> device{};
    };
    void SetGrabFreeze(Device grab, u8 pointerMode, u8 keyboardMode);
    void RemovePointerGrab();
    void RemoveKeyboardGrab();
    void ReleaseWindowGrabs(const WindowNode* window);
    bool InputFrozen(Device device) const;
    bool FrozenBy(Device device, const ClientSession* client) const;
    void SubmitInput(InputChange event);
    void DispatchInput(const InputChange& event);
    void DrainPendingInput();
    void FinishInputStep(Device device, const InputChange& event, ClientSession* recipient);
    ClientSession* DeliverPointerButton(u16 button, bool down, s32 x, s32 y);
    ClientSession* DeliverKey(u16 keycode, bool down, s32 x, s32 y);
    void DeliverCurrentKey(u16 keycode, bool down);
    bool KeyboardInputScheduled() const {
        return grabFreeze[Pointer].device[Keys] != Freeze::Running ||
               grabFreeze[Keys].device[Keys] != Freeze::Running || stepBothOwner;
    }
    static Device InputDevice(InputKind kind) { return kind == InputKind::Key ? Keys : Pointer; }
    const ClientSession* GrabOwner(Device grab) const { return grab == Pointer ? grab_pointer_client : grab_keyboard_client; }

    std::array<GrabFreeze, 2> grabFreeze{};
    std::array<std::optional<std::deque<InputChange>>, 2> pendingInput;
    std::array<std::optional<InputChange>, 2> replayEvents;
    const ClientSession* stepBothOwner{};
    std::optional<u32> eventState;
    u64 inputOrder{};
    u32 eventTime{};
    s16 pointerX{}, pointerY{};
    u16 pointerButtons{};
    bool drainingInput{};
    DisplayRuntime& server;
    bool has_window_mapped{};
    sp<WindowNode> root_window;
    WindowNode* grab_keyboard_window{};
    u8 focus_revert_to{RevertToNone};
    u32 last_focus_time{};
    WindowNode* focus_window{};
    WindowNode* motion_window{};
    ClientSession* grab_pointer_client{};
    ClientSession* grab_keyboard_client{};
    WindowNode* grab_confine_window{};
    WindowNode* grab_pointer_window{};
    sp<PointerSprite> grab_cursor;
    bool grab_pointer_passive{};
    u32 last_pointer_grab_time{};
    bool grab_pointer_owner_events{};
    bool grab_keyboard_owner_events{};
    u32 last_keyboard_grab_time{};
    u32 grab_event_mask{};
    struct CompositeRedirect { ClientSession* client; WindowNode* window; u8 update; };
    std::vector<CompositeRedirect> composite_redirects;
};

}  // namespace ludashi::x11
