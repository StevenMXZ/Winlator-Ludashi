#pragma once

#include "base/types.h"
#include <span>
#include <vector>

namespace xorg {

class Property {
public:
    Property(u32 name, u32 type, u32 format) : name(name), type(type), format(format) {}

    void ReplaceData(const std::vector<u8>& new_data) { data = new_data; }

    void PrependData(const std::vector<u8>& extra_data) {
        data.insert(data.begin(), extra_data.begin(), extra_data.end());
    }

    void AppendData(const std::vector<u8>& extra_data) {
        data.insert(data.end(), extra_data.begin(), extra_data.end());
    }

    std::span<const u8> GetData() const { return data; }

    const u32 name;
    const u32 type;
    const u32 format;

private:
    std::vector<u8> data;
};

}  // namespace xorg