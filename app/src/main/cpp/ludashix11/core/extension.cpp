// clang-format off
#include <X11/Xmd.h>
#include <X11/extensions/bigreqsproto.h>
#include <X11/extensions/shmproto.h>
#include <X11/extensions/shm.h>
// clang-format on
#include "core/extension.h"
#include "core/xclient.h"

namespace xorg {

ExtensionManager::ExtensionManager() {
    AddExtension(
            XBigReqExtensionName,
            [](XClient* client) { client->ProcBigRequest(); },
            XBigReqNumberEvents,
            XBigReqNumberErrors);
    AddExtension(
            SHMNAME,
            [](XClient* client) { client->ProcShmRequest(); },
            ShmNumberEvents,
            ShmNumberErrors);
    AddExtension("DRI3", [](XClient* c) { c->ProcDRI3(); }, 0, 0);
    AddExtension("Present", [](XClient* c) { c->ProcPresent(); }, 0, 0);
    AddExtension("SYNC", [](XClient* c) { c->ProcSync(); }, 0, 1);
    AddExtension("RANDR", [](XClient* c) { c->ProcRandR(); }, 2, 0);
    AddExtension("GLX", [](XClient* c) { c->ProcGLX(); }, 0, 14);
    // Match public Ludashi's Composite 0.1 window-redirection subset.
    AddExtension("Composite", [](XClient* c) { c->ProcComposite(); }, 0, 0);
    // RENDER cursor subset; Wine GDI is kept on its existing core path.
    AddExtension("RENDER", [](XClient* c) { c->ProcRender(); }, 0, 5);
    AddExtension("XInputExtension", [](XClient* c) { c->ProcXInput(); }, 17, 5);
}

ExtensionManager::~ExtensionManager() {}

void ExtensionManager::AddExtension(const std::string& name,
                                    const ExtensionRequestHandler& handler,
                                    u32 num_events,
                                    u32 num_errors) {
    const auto index = static_cast<u32>(extensions.size());
    Extension ext{
            .name = name,
            .handler = handler,
            .index = index,
            .base = kExtensionBase + index,
    };
    if (num_events) {
        ext.event_base = last_event;
        ext.event_last = last_event + num_events;
        last_event += num_events;
    }
    if (num_errors) {
        ext.error_base = last_error;
        ext.error_last = last_error + num_errors;
        last_error += num_errors;
    }
    extensions.push_back(ext);
}

Extension* ExtensionManager::FindExtension(const std::string& name) {
    for (auto& ext : extensions) {
        if (ext.name == name) {
            return &ext;
        }
    }
    return nullptr;
}

Extension* ExtensionManager::GetExtension(u8 opcode) {
    if (const auto base = opcode - kExtensionBase; base < extensions.size()) {
        return &extensions[base];
    }
    return nullptr;
}

}  // namespace xorg

