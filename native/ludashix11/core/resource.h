#pragma once

#include <queue>
#include "base/types.h"

namespace xorg {

class ResourceIDs {
public:
    explicit ResourceIDs(const u32 max_clients) {
        u32 clients_bits = 32 - std::countl_zero(max_clients);
        if (std::popcount(max_clients) == 1) {
            clients_bits -= 1;
        }
        const u32 base = 29 - clients_bits;
        id_mask = (1 << base) - 1;
        for (u32 i = 1; i < max_clients; i++) {
            id_bases.push(i << base);
        }
    }

    u32 GetMask() const { return id_mask; }

    u32 Acquire() {
        if (id_bases.empty()) {
            throw std::runtime_error("No more resource id available");
        }
        const u32 id_base = id_bases.front();
        id_bases.pop();
        return id_base;
    }

    bool IsInInterval(const u32 value, const u32 id_base) const {
        return (value | id_mask) == (id_base | id_mask);
    }

    void Recycle(const u32 id_base) { id_bases.push(id_base); }

private:
    std::queue<u32> id_bases;
    u32 id_mask;
};

}  // namespace xorg