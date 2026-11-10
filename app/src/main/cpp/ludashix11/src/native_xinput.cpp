#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/extension.h"
#include <array>
#include <cstring>

namespace xorg {
namespace {
constexpr u32 rawEvents = (1u << 15) | (1u << 16) | (1u << 17);

template<class Buffer, typename T> void store(Buffer& buffer, size_t offset, T value) {
    memcpy(buffer.data() + offset, &value, sizeof(value));
}

struct Wire {
    std::vector<u8> data;
    template<typename T> void put(T value) {
        const size_t offset = data.size(); data.resize(offset + sizeof(value));
        store(data, offset, value);
    }
    void byte(u8 value) { put(value); }
    void word(u16 value) { put(value); }
    void dword(u32 value) { put(value); }
    void zeros(size_t count) { data.resize(data.size() + count); }
    void name(const std::string& value) {
        data.insert(data.end(), value.begin(), value.end());
        zeros((-value.size()) & 3);
    }
};
}

void XServer::SelectRawInput(XClient* client, bool enabled) {
    if (enabled) rawInputClients.insert(client);
    else rawInputClients.erase(client);
}

void XServer::SendRawMotion(s32 dx, s32 dy) {
    if (!dx && !dy) return;
    // No allocations or client-table scan when XI2 has no subscribers.
    for (auto* client : rawInputClients) client->SendXIRawEvent(17, 0, dx, dy);
}

void XServer::SendRawButton(u16 button, bool down) {
    for (auto* client : rawInputClients) client->SendXIRawEvent(down ? 15 : 16, button);
}

void XClient::SendXIRawEvent(u16 type, u32 detail, s32 dx, s32 dy) {
    if (is_dead || !(xiRawMask & (1u << type))) return;
    std::array<u8, 68> packet{};
    packet[0] = GenericEvent; packet[1] = xiOpcode;
    store(packet, 2, sequence);
    store(packet, 4, u32(type == 17 ? 9 : 0));
    store(packet, 8, type); store(packet, 10, u16(2));
    store(packet, 12, server.GetTimestamp()); store(packet, 16, detail);
    store(packet, 20, u16(4)); store(packet, 22, u16(type == 17 ? 1 : 0));
    if (type == 17) {
        store(packet, 32, u32(3));
        // FP3232 is an integral word followed by an unsigned fractional word.
        store(packet, 36, dx); store(packet, 44, dy);
        store(packet, 52, dx); store(packet, 60, dy);
    }
    WriteImmediately(packet.data(), type == 17 ? packet.size() : 32);
}

void XClient::ProcXInput() {
    const auto* request = GetRequestData<xReq>();
    const u8 minor = request->data;
    const auto* extension = server.GetExtensionManager().FindExtension("XInputExtension");
    xiOpcode = extension->base;
    auto word = [&](size_t offset) {
        if (offset + 2 > request_size) throw BadLengthError();
        u16 value; memcpy(&value, request_buffer.data() + offset, 2); return value;
    };
    auto dword = [&](size_t offset) {
        if (offset + 4 > request_size) throw BadLengthError();
        u32 value; memcpy(&value, request_buffer.data() + offset, 4); return value;
    };
    auto size = [&](size_t expected) { if (request_size != expected) throw BadLengthError(); };
    auto badDevice = [&](u16 id) { throw XRequestError(extension->error_base, id); };
    auto reply = [&](Wire& packet) {
        packet.data[0] = X_Reply; packet.data[1] = minor;
        store(packet.data, 2, sequence);
        store(packet.data, 4, u32((packet.data.size() - 32) / 4));
        WriteImmediately(packet.data.data(), packet.data.size());
    };
    Wire packet; packet.zeros(32);
    switch (minor) {
    case 1: { // XI1 GetExtensionVersion
        if (request_size < 8 || 8 + ((word(4) + 3u) & ~3u) != request_size) throw BadLengthError();
        store(packet.data, 8, u16(2)); store(packet.data, 10, u16(2)); packet.data[12] = 1;
        reply(packet); return;
    }
    case 2: // No XI1 tablets.
        size(4); reply(packet); return;
    case 3: case 28: {
        size(8); const u8 device = request_buffer[4];
        if (device != 4) badDevice(device);
        if (minor == 28) {
            packet.data[8] = 7;
            for (u8 button = 1; button <= 7; ++button) packet.byte(button);
            packet.byte(0);
        }
        reply(packet); return;
    }
    case 47: {
        size(8); u16 major = word(4), version = word(6);
        if (major < 2) throw BadValueError(major);
        if (major > 2 || version > 2) { major = 2; version = 2; }
        store(packet.data, 8, major); store(packet.data, 10, version);
        reply(packet); return;
    }
    case 45:
        size(8); packet.data[8] = 1; store(packet.data, 10, u16(2)); reply(packet); return;
    case 41: {
        size(36);
        const u32 sourceId = dword(4), destinationId = dword(8);
        const u16 device = word(32);
        if (device != 2 && device != 4) badDevice(device);
        auto* source = sourceId ? GetResourceAs<Window>(sourceId) : nullptr;
        auto* destination = destinationId ? GetResourceAs<Window>(destinationId) : nullptr;
        if (sourceId && !source) throw BadWindowError(sourceId);
        if (destinationId && !destination) throw BadWindowError(destinationId);
        auto& mouse = server.GetMouse();
        if (source) {
            const double left = source->GetInRect().left + s32(dword(12)) / 65536.0;
            const double top = source->GetInRect().top + s32(dword(16)) / 65536.0;
            const double right = word(20) ? left + word(20) : source->GetInRect().right;
            const double bottom = word(22) ? top + word(22) : source->GetInRect().bottom;
            if (mouse.GetX() < left || mouse.GetX() >= right || mouse.GetY() < top || mouse.GetY() >= bottom) return;
        }
        const s32 x = s32(std::floor(s32(dword(24)) / 65536.0));
        const s32 y = s32(std::floor(s32(dword(28)) / 65536.0));
        mouse.WarpTo(x + (destination ? destination->GetInRect().left : mouse.GetX()),
                     y + (destination ? destination->GetInRect().top : mouse.GetY()));
        return;
    }
    case 46: {
        const u32 window = dword(4); const u16 count = word(8);
        if (request_size < 12) throw BadLengthError();
        if (!GetResourceAs<Window>(window)) throw BadWindowError(window);
        std::unordered_map<u16, u32> changes;
        size_t offset = 12;
        for (u16 i = 0; i < count; ++i) {
            const u16 device = word(offset), words = word(offset + 2);
            offset += 4;
            if (size_t(words) * 4 > request_size - offset) throw BadLengthError();
            if (device > 4) badDevice(device);
            changes[device] = words ? dword(offset) : 0;
            offset += size_t(words) * 4;
        }
        if (offset != request_size) throw BadLengthError();
        // This subset delivers raw events only on the immortal root window.
        if (window != server.GetWindowManager().GetRootWindow()->id) return;
        auto& masks = xiSelections[window];
        for (const auto& [device, mask] : changes) {
            if (mask) masks[device] = mask;
            else masks.erase(device);
        }
        if (masks.empty()) xiSelections.erase(window);
        xiRawMask = 0;
        const auto root = xiSelections.find(server.GetWindowManager().GetRootWindow()->id);
        if (root != xiSelections.end()) for (u16 device : {0, 1, 2}) {
            const auto mask = root->second.find(device);
            if (mask != root->second.end()) xiRawMask |= mask->second & rawEvents;
        }
        server.SelectRawInput(this, xiRawMask != 0); return;
    }
    case 48: {
        size(8); const u16 wanted = word(4);
        struct Device { u16 id, use, attachment; const char* name; bool pointer; };
        const Device devices[] = {{2, 1, 3, "Virtual core pointer", true},
                                  {3, 2, 2, "Virtual core keyboard", false},
                                  {4, 3, 2, "Virtual slave pointer", true}};
        u16 count{};
        for (const auto& device : devices) {
            if (wanted != 0 && wanted != device.id && !(wanted == 1 && device.id != 4)) continue;
            ++count;
            const std::string name = device.name;
            packet.word(device.id); packet.word(device.use); packet.word(device.attachment);
            packet.word(device.pointer ? 3 : 0); packet.word(name.size());
            packet.byte(1); packet.byte(0); packet.name(name);
            if (!device.pointer) continue;
            packet.word(1); packet.word(10); packet.word(4); packet.word(7);
            packet.dword((server.GetMouse().GetButtons() >> 7) & 0xfe);
            packet.zeros(7 * 4);
            for (u16 axis : {0, 1}) {
                packet.word(2); packet.word(11); packet.word(4); packet.word(axis);
                packet.zeros(4 + 3 * 8 + 4);
                packet.byte(0); packet.zeros(3);
            }
        }
        if (!count) badDevice(wanted);
        store(packet.data, 8, count); reply(packet); return;
    }
    // Match the Java subset for state requests that do not expect a reply.
    case 4: case 6: case 42: case 43: case 44: case 49:
    case 52: case 53: case 57: case 58: case 61:
        return;
    default: throw BadRequestError();
    }
}
}
