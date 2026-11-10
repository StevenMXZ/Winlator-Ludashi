#pragma once

#include "base/types.h"
#include <unordered_map>

namespace xorg {

class Window;
class XClient;

struct Selection {
    Window* owner;
    XClient* client;
};

class SelectionManager {
public:
    SelectionManager();
    ~SelectionManager();

    void SetSelection(u32 atom, Window* owner, XClient* client, u32 timestamp);

    Selection GetSelection(u32 atom);
    void RemoveClient(XClient*);
    void RemoveWindow(Window*);

private:
    std::pmr::unordered_map<u32, Selection> selections;
};

}  // namespace xorg