#include "core/wire_extensions.h"
#include "core/client_session.h"

#include <algorithm>
#include <iterator>

namespace ludashi::x11 {

// Keep the wire assignments stable. Extensions without legacy events or
// errors advertise zero in the corresponding QueryExtension field.
constexpr ExtensionWireInfo WireExtensions::extensions[] = {
    {"BIG-REQUESTS", &ClientSession::ProcBigRequest,     128,  0,   0},
    {"MIT-SHM",      &ClientSession::ProcShmRequest,     129, 64, 128},
    {"DRI3",         &ClientSession::ProcDRI3,           130,  0,   0},
    {"Present",      &ClientSession::ProcPresent,        131,  0,   0},
    {"SYNC",         &ClientSession::ProcSync,           132,  0, 129},
    {"RANDR",        &ClientSession::ProcRandR,           133, 65,   0},
    {"GLX",          &ClientSession::ProcGLX,             134,  0, 130},
    {"Composite",    &ClientSession::ProcComposite,       135,  0,   0},
    {"RENDER",       &ClientSession::ProcRender,          136,  0, 144},
    {"XInputExtension", &ClientSession::ProcXInput,      137, 67, 149},
};

std::span<const ExtensionWireInfo> WireExtensions::All() {
    return extensions;
}

const ExtensionWireInfo* WireExtensions::Find(std::string_view name) {
    const auto extension = std::ranges::find(extensions, name, &ExtensionWireInfo::name);
    return extension == std::end(extensions) ? nullptr : extension;
}

const ExtensionWireInfo* WireExtensions::ForOpcode(uint8_t opcode) {
    const size_t index = static_cast<size_t>(opcode - kExtensionBase);
    return index < std::size(extensions) ? &extensions[index] : nullptr;
}

} // namespace ludashi::x11
