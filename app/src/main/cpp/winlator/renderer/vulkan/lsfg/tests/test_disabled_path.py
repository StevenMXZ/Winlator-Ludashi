"""Run production command recording and configuration with mocked Vulkan calls.

Requires a C++17 compiler and Vulkan headers (VULKAN_HEADERS can locate them).
No GPU, Android runtime or Lossless.dll is used.
"""
from pathlib import Path
import os
import subprocess
import tempfile


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    root = Path(__file__).resolve().parents[2]
    context = (root / 'VulkanRendererContext.cpp').read_text()
    filters = (root / 'VulkanRendererFilters.cpp').read_text()
    methods = '\n'.join(method(context, signature) for signature in [
        'uint32_t VulkanRendererContext::desiredFrameGenGenerations(',
        'uint32_t VulkanRendererContext::frameGenCapacity(',
        'bool VulkanRendererContext::frameGenerationActive(',
        'void VulkanRendererContext::configureFrameGeneration(',
    ]) + '\n' + method(filters, 'void VulkanRendererContext::recordCmdBuf(')
    harness = r'''
#include "context_public.h"
#include "lsfg/vkr_lsfg.h"
#include <cassert>
#include <cstdlib>
#include <stdexcept>
#include <iostream>
static int barriers, uploads, draws, renders, drains, rebuilds, cleanups;
static bool allowTransition = false;
static void forbidden() { std::abort(); }
VulkanRendererContext::VulkanRendererContext(ANativeWindow*, int, int, void*, bool) {}
VulkanRendererContext::~VulkanRendererContext() {}
bool VulkanRendererContext::ensureFrameGenerationResources() { assert(allowTransition); return false; }
void VulkanRendererContext::destroyFrameGeneration() { assert(allowTransition); }
void VulkanRendererContext::cleanupSwapchain() { assert(allowTransition); ++cleanups; }
void VulkanRendererContext::createSwapchain() { assert(allowTransition && !frameGenRequested); ++rebuilds; }
void VulkanRendererContext::createFramebuffers() { assert(allowTransition); }
void VulkanRendererContext::createCmdBufs() { assert(allowTransition); }
void VulkanRendererContext::processFrameGeneration(VkCommandBuffer, VkImage, uint32_t) { forbidden(); }
void VulkanRendererContext::generateFrameGeneration(VkCommandBuffer, uint32_t, uint32_t, VkImage, VkImageView) { forbidden(); }
void VulkanRendererContext::copyFrameGenTarget(VkCommandBuffer, const FrameGenTarget&, VkImage) { forbidden(); }
void VulkanRendererContext::transition(VkCommandBuffer, VkImage, VkImageLayout, VkImageLayout, VkAccessFlags, VkAccessFlags, VkPipelineStageFlags, VkPipelineStageFlags) { forbidden(); }
void VulkanRendererContext::transitionGeneratedSwapchainImage(VkCommandBuffer, VkImage) { forbidden(); }
VkPipeline VulkanRendererGetOpaquePipeline(VulkanRendererContext* ctx) { return ctx->pipeline; }
extern "C" void vkr_lsfg_process(VkrLsfg*, VkCommandBuffer, VkImage, uint32_t, uint32_t, uint32_t) { forbidden(); }
extern "C" void vkr_lsfg_generate_into(VkrLsfg*, VkCommandBuffer, uint32_t, uint32_t, VkImage, VkImageView, uint32_t, uint32_t) { forbidden(); }
''' + methods + r'''
int main() {
    VulkanRendererContext ctx(nullptr, 0, 0, nullptr, false);
    ctx.device = (VkDevice)1;
    ctx.vk_.DeviceWaitIdle = [](VkDevice) { ++drains; return VK_SUCCESS; };
    ctx.needsRender.store(false);
    for (int engine = 0; engine < 2; ++engine) for (int i = 0; i < 1000; ++i) {
        ctx.frameGenEngine = engine;
        ctx.configureFrameGeneration(false, i % 2 ? "pending-cache" : "", 2 + i % 3,
                                     i % 2 ? 60 : 120, 25 + i % 76, 120);
        assert(!ctx.frameGenerationActive());
    }
    assert(drains == 0 && !ctx.needsRender.load());
    assert(ctx.frameGen == nullptr && !ctx.frameGenDispatchReady && ctx.wfg == nullptr && !ctx.wfgDispatchReady);
    assert(ctx.frameGenTargets.empty() && ctx.frameGenRenderDoneSems.empty());

    // A failed active quality/cache update must also restore normal swapchain policy.
    allowTransition = true;
    ctx.swapchain = (VkSwapchainKHR)1;
    ctx.frameGenEngine = 0;
    ctx.frameGenRequested = true;
    ctx.frameGenMultiplier = 2; ctx.frameGenTargetRate = 0;
    ctx.frameGenFlowScale = 0.70f; ctx.frameGenCachePath = "pending-cache";
    ctx.configureFrameGeneration(true, "pending-cache", 2, 0, 50, 120);
    assert(!ctx.frameGenRequested && drains == 1 && rebuilds == 1 && cleanups == 1);
    ctx.needsRender.store(false);
    ctx.configureFrameGeneration(false, "pending-cache", 2, 0, 70, 120);
    assert(drains == 1 && rebuilds == 1 && !ctx.needsRender.load());
    // An explicit disable after an active session tears down once, then stays cheap.
    ctx.frameGenRequested = true;
    ctx.configureFrameGeneration(false, "pending-cache", 2, 0, 70, 120);
    assert(!ctx.frameGenRequested && drains == 2 && rebuilds == 2 && cleanups == 2);
    ctx.needsRender.store(false);
    ctx.configureFrameGeneration(false, "pending-cache", 4, 120, 40, 120);
    assert(drains == 2 && rebuilds == 2 && !ctx.needsRender.load());
    allowTransition = false;

    ctx.renderPass = (VkRenderPass)11;
    ctx.swapchainFBs = {(VkFramebuffer)12};
    ctx.swapchainExt = {1280, 720};
    ctx.pipeline = (VkPipeline)13;
    ctx.vk_.BeginCommandBuffer = [](VkCommandBuffer, const VkCommandBufferBeginInfo*) { return VK_SUCCESS; };
    ctx.vk_.EndCommandBuffer = [](VkCommandBuffer) { return VK_SUCCESS; };
    ctx.vk_.CmdBeginRenderPass = [](VkCommandBuffer, const VkRenderPassBeginInfo* info, VkSubpassContents) {
        assert(info->renderPass == (VkRenderPass)11 && info->framebuffer == (VkFramebuffer)12);
        ++renders;
    };
    ctx.vk_.CmdEndRenderPass = [](VkCommandBuffer) {};
    ctx.vk_.CmdSetViewport = [](VkCommandBuffer, uint32_t, uint32_t, const VkViewport*) {};
    ctx.vk_.CmdSetScissor = [](VkCommandBuffer, uint32_t, uint32_t, const VkRect2D*) {};
    ctx.vk_.CmdBindPipeline = [](VkCommandBuffer, VkPipelineBindPoint point, VkPipeline) {
        assert(point == VK_PIPELINE_BIND_POINT_GRAPHICS);
    };
    ctx.vk_.CmdBindDescriptorSets = [](VkCommandBuffer, VkPipelineBindPoint, VkPipelineLayout, uint32_t,
                                    uint32_t, const VkDescriptorSet*, uint32_t, const uint32_t*) {};
    ctx.vk_.CmdPushConstants = [](VkCommandBuffer, VkPipelineLayout, VkShaderStageFlags, uint32_t, uint32_t, const void*) {};
    ctx.vk_.CmdDraw = [](VkCommandBuffer, uint32_t, uint32_t, uint32_t, uint32_t) { ++draws; };
    ctx.vk_.CmdPipelineBarrier = [](VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags,
            VkDependencyFlags, uint32_t, const VkMemoryBarrier*, uint32_t, const VkBufferMemoryBarrier*,
            uint32_t count, const VkImageMemoryBarrier*) { assert(count == 1); ++barriers; };
    ctx.vk_.CmdCopyBufferToImage = [](VkCommandBuffer, VkBuffer, VkImage, VkImageLayout,
                                    uint32_t, const VkBufferImageCopy*) { ++uploads; };
    ctx.vk_.CmdCopyImage = [](VkCommandBuffer, VkImage, VkImageLayout, VkImage, VkImageLayout,
                            uint32_t, const VkImageCopy*) { forbidden(); };
    std::vector<VkImageMemoryBarrier> ahb, pre, post;
    for (int mode = 0; mode <= 5; ++mode) for (int scenario = 0; scenario < 4; ++scenario) {
        ctx.filterMode = mode;
        ctx.sgsrPipeline = (VkPipeline)21;
        ctx.fsr1Pipeline = (VkPipeline)22;
        ctx.lanczosPipeline = (VkPipeline)23;
        ctx.stretchPipeline = (VkPipeline)24;
        ctx.postfxPipeline = (VkPipeline)25;
        ctx.stretchMode = scenario == 3 ? 1 : 0;
        ctx.postFXMode = scenario == 3 ? 1 : 0;
        bool cursor = scenario == 3;
        ctx.cursorImg = cursor ? (VkImage)31 : VK_NULL_HANDLE;
        ctx.cursorDS = cursor ? (VkDescriptorSet)32 : VK_NULL_HANDLE;
        std::vector<VulkanRendererContext::DrawEntry> scene(1);
        scene[0].img = (VkImage)41;
        scene[0].ds = (VkDescriptorSet)42;
        scene[0].w = 1280; scene[0].h = 720;
        scene[0].isAHB = scenario != 2;
        scene[0].needsTransition = scenario == 1;
        scene[0].upload = scenario == 2 ? (VkBuffer)43 : VK_NULL_HANDLE;
        barriers = uploads = draws = renders = 0;
        ctx.recordCmdBuf((VkCommandBuffer)1, 0, scene, ahb, pre, post,
                         VK_NULL_HANDLE, false, 0, 0, 1, 1, 1280, 720,
                         20, 20, 0, 0, 16, 16, cursor, {{0, 0}, {1280, 720}}, 3, false);
        assert(renders == 1 && draws == (cursor ? 2 : 1));
        assert(uploads == (scenario == 2 ? 1 : 0));
        assert(barriers == (scenario == 1 ? 1 : scenario == 2 ? 2 : 0));
        assert(ctx.frameGenTargets.empty() && ctx.frameGenGeneratedFrames.load() == 0);
    }
    std::cout << "PASS: 24 FG-off recording cases, direct swapchain, no extra copies/compute; "
                 "2000 disabled configuration changes across LSFG/WFG without GPU drain, resources or repaint; normal swapchain restored after disable/failure\n";
}
'''
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        (temp / 'android').mkdir()
        (temp / 'android/hardware_buffer.h').write_text('struct AHardwareBuffer;\n')
        (temp / 'android/native_window.h').write_text('struct ANativeWindow;\n')
        (temp / 'jni.h').write_text('')
        (temp / 'android/log.h').write_text(
            '#define ANDROID_LOG_DEBUG 3\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n'
            '#define ANDROID_LOG_ERROR 6\nstatic inline int __android_log_print(int, const char*, const char*, ...) { return 0; }\n')
        (temp / 'context_public.h').write_text((root / 'VulkanRendererContext.h').read_text().replace('private:', 'public:'))
        source, binary = temp / 'disabled.cpp', temp / 'disabled'
        source.write_text(harness)
        command = [os.environ.get('CXX', 'c++'), '-std=c++17', '-O2', '-pthread',
                   '-I', str(temp), '-I', str(root)]
        if os.environ.get('VULKAN_HEADERS'):
            command += ['-I', os.environ['VULKAN_HEADERS']]
        subprocess.run(command + [str(source), '-o', str(binary)], check=True, timeout=60)
        subprocess.run([str(binary)], check=True, timeout=30)


if __name__ == '__main__':
    main()
