#include "lsfg_dxbc.h"
#include <dxbc_module.h>
#include <cstdlib>
#include <cstring>

extern "C" __attribute__((visibility("default")))
bool lsfg_translate_dxbc(const uint8_t* data, uint32_t size, uint32_t** words, uint32_t* count) {
    if (!data || size < 32 || std::memcmp(data, "DXBC", 4) || !words || !count) return false;
    const auto read = [data](uint32_t offset) { uint32_t value; std::memcpy(&value, data + offset, 4); return value; };
    const uint32_t total = read(24), chunks = read(28);
    if (total < 32 || total > size || chunks > (total - 32) / 4) return false;
    bool compute = false;
    for (uint32_t i = 0; i < chunks; ++i) {
        const uint32_t offset = read(32 + i * 4);
        if (offset < 32 + chunks * 4 || offset > total - 8 || read(offset + 4) > total - offset - 8) return false;
        if (!std::memcmp(data + offset, "SHDR", 4) || !std::memcmp(data + offset, "SHEX", 4)) {
            const uint32_t bytes = read(offset + 4);
            if (bytes < 8 || read(offset + 8) >> 16 != 5 || read(offset + 12) < 2 || read(offset + 12) > bytes / 4) return false;
            compute = true;
        }
    }
    if (!compute) return false;
    try {
        dxvk::DxbcReader reader(reinterpret_cast<const char*>(data), size);
        dxvk::DxbcModule module(reader);
        auto code = module.compile(dxvk::DxbcModuleInfo{}, "LSFG");
        uint32_t binding = 0;
        for (auto instruction : code) {
            if (instruction.opCode() == spv::OpFunction) break;
            if (instruction.opCode() == spv::OpDecorate && instruction.arg(2) == spv::DecorationBinding)
                code.data()[instruction.offset() + 3] = binding++;
        }
        if (!code.size() || code.size() % sizeof(uint32_t) || code.size() > 64 * 1024 * 1024) return false;
        auto* output = static_cast<uint32_t*>(std::malloc(code.size()));
        if (!output) return false;
        std::memcpy(output, code.data(), code.size());
        *words = output;
        *count = static_cast<uint32_t>(code.size() / sizeof(uint32_t));
        return true;
    } catch (...) {
        return false;
    }
}
