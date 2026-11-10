#pragma once

#include <functional>
#include <string>
#include <vector>
#include "base/types.h"

namespace xorg {

constexpr u32 kExtensionBase = 128;

class XClient;

using ExtensionRequestHandler = std::function<void(XClient*)>;

struct Extension {
    std::string name;
    ExtensionRequestHandler handler;
    u32 index;
    u32 base;
    u32 event_base;
    u32 event_last;
    u32 error_base;
    u32 error_last;
};

class ExtensionManager {
public:
    ExtensionManager();

    ~ExtensionManager();

    void AddExtension(const std::string& name,
                      const ExtensionRequestHandler& handler,
                      u32 num_events,
                      u32 num_errors);

    Extension* FindExtension(const std::string& name);

    Extension* GetExtension(u8 opcode);

private:
    std::vector<Extension> extensions;
    u32 last_event{64};
    u32 last_error{128};
};

}  // namespace xorg