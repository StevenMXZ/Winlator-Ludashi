#!/usr/bin/env python3
"""Execute the WFG adapter and production LSFG pacer with a deterministic clock."""
from pathlib import Path
import os
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[2]
    wfg = root / 'wfg'
    pacer = (root / 'lsfg/lsfg_pacer.cpp').read_text()
    # Replace only the time source; exercise the production planning algorithm.
    pacer = pacer.replace('const Clock::time_point now = Clock::now();',
                          'const Clock::time_point now = ::testNow;')
    harness = r'''
#include <chrono>
#include <cassert>
#include <cmath>
#include <iostream>
#include "vkr_dis.h"
#include "wfg/wfg_quality.h"
static std::chrono::steady_clock::time_point testNow{std::chrono::seconds(1)};
struct VkrDis { bool built{}, direct{}; uint32_t side{}, processed{}, generated{}; };
static VkrDis* dis;
extern "C" {
bool vkr_dis_bind_dispatch(PFN_vkGetInstanceProcAddr, VkInstance) { return true; }
VkrDis* vkr_dis_create(VkDevice, VkPhysicalDevice) { return dis = new VkrDis; }
void vkr_dis_destroy(VkrDis* d) { delete d; dis = nullptr; }
void vkr_dis_set_direct_output(VkrDis* d, bool direct) { d->direct = direct; }
void vkr_dis_configure(VkrDis* d, uint32_t side, uint32_t, float) { d->side = side; }
bool vkr_dis_needs_rebuild(const VkrDis* d, uint32_t, uint32_t, VkFormat, VkrDisContentRect) { return !d->built; }
bool vkr_dis_prepare(VkrDis* d, uint32_t, uint32_t, VkFormat, VkrDisContentRect) { d->built = true; return true; }
void vkr_dis_process(VkrDis* d, VkCommandBuffer, VkImage, uint32_t, uint32_t, uint32_t) { ++d->processed; }
void vkr_dis_generate_into(VkrDis* d, VkCommandBuffer, uint32_t, uint32_t, VkImage, VkImageView, uint32_t, uint32_t, VkImage) { ++d->generated; }
void vkr_dis_forget_targets(VkrDis*) {}
void vkr_dis_reset(VkrDis*) {}
}
''' + pacer + '\n#include "wfg/vkr_wfg.cpp"\n' + r'''
static uint32_t run(int source, int target, int multiplier, float hz, int frames = 300) {
    auto* e = vkr_wfg_create((VkDevice)1, (VkPhysicalDevice)2);
    assert(dis->direct);
    vkr_wfg_configure(e, multiplier, target, 50, hz);
    assert(dis->side == 180);
    assert(vkr_wfg_prepare(e, 1280, 720, VK_FORMAT_R8G8B8A8_UNORM));
    uint32_t made = 0;
    const auto step = std::chrono::nanoseconds(1000000000LL / source);
    for (int i = 1; i <= frames; ++i) {
        testNow += step;
        const auto count = vkr_wfg_plan(e, 3, i, hz);
        assert(count <= 3);
        vkr_wfg_process(e, (VkCommandBuffer)1, (VkImage)2, 1280, 720, count);
        if (i > 100) made += count;
        const auto processed = dis->processed;
        // Cursor/UI redraws between source updates must do no history/motion work.
        for (int j = 0; j < 5; ++j) {
            assert(vkr_wfg_plan(e, 3, i, hz) == 0);
            vkr_wfg_process(e, (VkCommandBuffer)1, (VkImage)2, 1280, 720, 0);
        }
        assert(dis->processed == processed);
    }
    if (source >= hz || source < 20) assert(dis->processed <= 15);
    else assert(dis->processed == (uint32_t)frames);
    vkr_wfg_destroy(e);
    return made;
}
int main() {
    static_assert(wfgFlowSide(40) == 128 && wfgFlowSide(50) == 180);
    static_assert(wfgFlowSide(70) == 252 && wfgFlowSide(100) == 360);
    static_assert(wfgFlowSide(45) == 128 && wfgFlowSide(60) == 180 && wfgFlowSide(85) == 252);
    assert(run(30, 0, 2, 120) == 200); // fixed 2x
    assert(run(30, 0, 3, 120) == 400); // fixed 3x
    assert(run(30, 0, 4, 120) == 600); // fixed 4x
    assert(run(30, 120, 2, 120) == 600); // adaptive uses available 4x ceiling
    assert(run(60, 120, 2, 60) == 0); // no slots above panel refresh
    assert(run(15, 120, 4, 120) == 0); // DIS motion-range safety floor
    // 45 -> 120 needs fractional output scheduling (rather than integer ladder/drop).
    const auto fractional = run(45, 120, 2, 120);
    assert(fractional >= 330 && fractional <= 336);
    std::cout << "PASS: WFG quality mapping, 2x/3x/4x, adaptive/fractional cadence, refresh cap, low FPS floor and UI-only redraws\n";
}
'''
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        source = temp / 'backend.cpp'
        source.write_text(harness)
        command = [os.environ.get('CXX', 'c++'), '-std=c++17', '-O2', '-I', str(root), '-I', str(root / 'lsfg'),
                   '-I', str(root.parents[2] / 'wfg/include')]
        if os.environ.get('VULKAN_HEADERS'):
            command += ['-I', os.environ['VULKAN_HEADERS']]
        binary = temp / 'backend'
        subprocess.run(command + [str(source), '-o', str(binary)], check=True, timeout=60)
        subprocess.run([str(binary)], check=True, timeout=30)


if __name__ == '__main__':
    main()
