#!/usr/bin/env python3
"""Validate Vulkan 1.1 shaders and execute WFG command recording with mocked Vulkan."""
from pathlib import Path
import os
import shutil
import struct
import subprocess
import tempfile


def main():
    cpp = Path(__file__).resolve().parents[5]
    engine = cpp / 'wfg'
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        (temp / 'shaders').mkdir()
        (temp / 'android').mkdir()
        (temp / 'android/log.h').write_text(
            '#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_ERROR 6\n'
            'static inline int __android_log_print(int p,const char*t,const char*f,...) {return 0;}\n')
        compiler = shutil.which('glslangValidator')
        validator = shutil.which('spirv-val')
        if not compiler or not validator:
            raise RuntimeError('Install glslang-tools and spirv-tools to validate the Vulkan 1.1 path')
        for source in sorted((engine / 'include/shaders').glob('*.comp')):
            name = source.stem + '_comp'
            binary = temp / (name + '.spv')
            subprocess.run([compiler, '-V', '--target-env', 'vulkan1.1', '-Os', str(source),
                            '-o', str(binary)], check=True, stdout=subprocess.DEVNULL)
            raw = binary.read_bytes()
            assert struct.unpack_from('<I', raw, 4)[0] <= 0x10300, source
            subprocess.run([validator, '--target-env', 'vulkan1.1', str(binary)], check=True)
            words = struct.unpack('<' + 'I' * (len(raw) // 4), raw)
            (temp / 'shaders' / (name + '.spv.h')).write_text(
                '#include <stdint.h>\n#include <stddef.h>\nstatic const uint32_t ' + name + '[]={' +
                ','.join(hex(word) for word in words) + '};\nstatic const size_t ' + name + '_size=sizeof(' + name + ');\n')
        harness = r'''
#include <assert.h>
#include "vkr_dis.c"
WfgVkDispatch wfg_vkd;
static unsigned dispatches, blits, writes, barriers;
static VkDescriptorSet bound[8];
static VkImageView boundViews[8];
static void bindPipeline(VkCommandBuffer c, VkPipelineBindPoint b, VkPipeline p) {}
static void bindSets(VkCommandBuffer c, VkPipelineBindPoint b, VkPipelineLayout l,
                     uint32_t first, uint32_t count, const VkDescriptorSet* sets,
                     uint32_t dyn, const uint32_t* offsets) { bound[dispatches] = sets[0]; }
static void push(VkCommandBuffer c, VkPipelineLayout l, VkShaderStageFlags s,
                 uint32_t o, uint32_t n, const void* data) {}
static void dispatch(VkCommandBuffer c, uint32_t x, uint32_t y, uint32_t z) {
    assert(x == 160 && y == 90 && z == 1); ++dispatches;
}
static void barrier(VkCommandBuffer c, VkPipelineStageFlags s, VkPipelineStageFlags d,
                    VkDependencyFlags flags, uint32_t a, const VkMemoryBarrier* b,
                    uint32_t e, const VkBufferMemoryBarrier* f,
                    uint32_t n, const VkImageMemoryBarrier* images) {
    assert(n == 1); ++barriers;
}
static void update(VkDevice d, uint32_t n, const VkWriteDescriptorSet* w,
                   uint32_t c, const VkCopyDescriptorSet* copies) {
    assert(n == 1 && w->dstBinding == 5 && w->descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
    boundViews[writes++] = w->pImageInfo->imageView;
}
static void blit(VkCommandBuffer c, VkImage s, VkImageLayout sl, VkImage d, VkImageLayout dl,
                 uint32_t n, const VkImageBlit* b, VkFilter f) { ++blits; }
int main(void) {
    wfg_vkd.CmdBindPipeline = bindPipeline;
    wfg_vkd.CmdBindDescriptorSets = bindSets;
    wfg_vkd.CmdPushConstants = push;
    wfg_vkd.CmdDispatch = dispatch;
    wfg_vkd.CmdPipelineBarrier = barrier;
    wfg_vkd.UpdateDescriptorSets = update;
    wfg_vkd.CmdBlitImage = blit;
    VkrDis d = {0};
    d.built = true; d.active_slot = 1; d.last_generations = 3;
    d.content = (VkrDisContentRect){0, 0, 1280, 720};
    d.built_full_extent = (VkExtent2D){1280, 720};
    d.view_interp_out = (VkImageView)(uintptr_t)300;
    d.interp_out.image = (VkImage)(uintptr_t)301;
    for (unsigned g = 0; g < 3; ++g) {
        d.interp_sets[1][0][g] = (VkDescriptorSet)(uintptr_t)(100 + g);
        vkr_dis_generate_into(&d, (VkCommandBuffer)1, g, g, (VkImage)(uintptr_t)(400 + g),
            (VkImageView)(uintptr_t)(500 + g), 1280, 720, VK_NULL_HANDLE);
    }
    assert(dispatches == 3 && blits == 0 && writes == 3 && barriers == 3);
    for (unsigned g = 0; g < 3; ++g) {
        assert(bound[g] == (VkDescriptorSet)(uintptr_t)(100 + g));
        assert(boundViews[g] == (VkImageView)(uintptr_t)(500 + g));
    }
    // Same target bindings are cached. An invalid output must record nothing.
    vkr_dis_generate_into(&d, (VkCommandBuffer)1, 0, 0, (VkImage)400, (VkImageView)500, 1280, 720, VK_NULL_HANDLE);
    assert(writes == 3 && dispatches == 4 && blits == 0);
    vkr_dis_generate_into(&d, (VkCommandBuffer)1, 3, 3, (VkImage)403, (VkImageView)503, 1280, 720, VK_NULL_HANDLE);
    assert(dispatches == 4);
    // Original no-view API retains its intermediate/blit fallback.
    vkr_dis_generate_into(&d, (VkCommandBuffer)1, 0, 0, (VkImage)400, VK_NULL_HANDLE, 1280, 720, VK_NULL_HANDLE);
    assert(dispatches == 5 && blits == 1 && writes == 4);
    assert(boundViews[3] == d.view_interp_out);
    vkr_dis_forget_targets(&d);
    assert(d.interp_targets[1][0][0] == VK_NULL_HANDLE);
    puts("PASS: 22 Vulkan 1.1 shaders; direct output without blit, independent generation bindings, descriptor cache and blit fallback");
}
'''
        source, binary = temp / 'record.c', temp / 'record'
        source.write_text(harness)
        command = [os.environ.get('CC', 'cc'), '-std=c11', '-D_POSIX_C_SOURCE=200809L', '-DVK_USE_PLATFORM_ANDROID_KHR', '-O2', '-ffunction-sections',
                   '-fdata-sections', '-Wl,--gc-sections', '-I', str(temp), '-I', str(engine / 'src'),
                   '-I', str(engine / 'include')]
        if os.environ.get('VULKAN_HEADERS'):
            command += ['-I', os.environ['VULKAN_HEADERS']]
        subprocess.run(command + [str(source), '-o', str(binary), '-lm'], check=True, timeout=60)
        subprocess.run([str(binary)], check=True, timeout=30)


if __name__ == '__main__':
    main()
