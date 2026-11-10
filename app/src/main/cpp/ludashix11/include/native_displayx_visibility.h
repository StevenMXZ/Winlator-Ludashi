#pragma once
#include "native_screen.h"

namespace xorg {
// Scene order is bottom to top. Only drop a lower layer if a single opaque
// upper layer covers its complete destination. Partial/transparent overlaps
// remain intact; buffer dimensions need not match the window destination.
inline void CullCoveredDisplayXLayers(std::vector<NativeEntry>& entries) {
    auto width = [](const NativeEntry& e) {
        return e.windowWidth > 0 ? e.windowWidth : int(e.drawable->GetWidth());
    };
    auto height = [](const NativeEntry& e) {
        return e.windowHeight > 0 ? e.windowHeight : int(e.drawable->GetHeight());
    };
    for (size_t i = entries.size(); i > 0;) {
        --i;
        const auto& lower = entries[i];
        for (size_t j = i + 1; j < entries.size(); ++j) {
            const auto& upper = entries[j];
            if (upper.opaque && upper.x <= lower.x && upper.y <= lower.y &&
                int64_t(upper.x) + width(upper) >= int64_t(lower.x) + width(lower) &&
                int64_t(upper.y) + height(upper) >= int64_t(lower.y) + height(lower)) {
                // A hidden, unsubmitted wrapper image must still release its
                // producer. Claimed images remain owned by their submission.
                if (lower.external) lower.external->Discard();
                entries.erase(entries.begin() + i);
                break;
            }
        }
    }
}
}
