#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace ludashi::x11 {

using CorePacket = std::array<uint8_t, 32>;

inline void PutWire16(CorePacket& packet, size_t offset, uint16_t value) {
    packet[offset] = static_cast<uint8_t>(value);
    packet[offset + 1] = static_cast<uint8_t>(value >> 8);
}

inline void PutWire32(CorePacket& packet, size_t offset, uint32_t value) {
    PutWire16(packet, offset, static_cast<uint16_t>(value));
    PutWire16(packet, offset + 2, static_cast<uint16_t>(value >> 16));
}

} // namespace ludashi::x11
