#pragma once

#include <cstdint>
#include <span>
#include <string_view>

namespace ludashi::x11 {

inline constexpr uint8_t kExtensionBase = 128;

class ClientSession;

struct ExtensionWireInfo {
    std::string_view name;
    void (ClientSession::*handler)();
    uint8_t base;
    uint8_t event_base;
    uint8_t error_base;
};

enum class ExtensionOpcode : uint8_t {
    BigRequests = kExtensionBase, SharedMemory, Dri3, Present, Sync, Randr,
    Glx, Composite, Render, XInput,
};

class WireExtensions {
public:
    static std::span<const ExtensionWireInfo> All();
    static const ExtensionWireInfo* Find(std::string_view name);
    static const ExtensionWireInfo* ForOpcode(uint8_t opcode);
    static const ExtensionWireInfo& At(ExtensionOpcode opcode) {
        return *ForOpcode(static_cast<uint8_t>(opcode));
    }

private:
    static const ExtensionWireInfo extensions[];
};

} // namespace ludashi::x11
