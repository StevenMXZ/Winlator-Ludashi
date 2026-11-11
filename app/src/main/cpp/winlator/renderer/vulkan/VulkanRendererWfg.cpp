#include "VulkanRendererContext.h"
#include "lsfg/vkr_lsfg.h"
#include "wfg/vkr_wfg.h"

void VulkanRendererContext::setFrameGenerationEngine(int engine) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    std::lock_guard<std::mutex> renderLock(renderMutex);
    // No engine switch while running. Container/shortcut selection is applied
    // before configureFrameGeneration, including after surface reattachment.
    if (!frameGenRequested) frameGenEngine = engine == 1 ? 1 : 0;
}

void VulkanRendererContext::processFrameGeneration(VkCommandBuffer cb, VkImage source,
                                                    uint32_t generatedCount) {
    if (frameGenEngine == 1)
        vkr_wfg_process(wfg, cb, source, swapchainExt.width, swapchainExt.height, generatedCount);
    else
        vkr_lsfg_process(frameGen, cb, source, swapchainExt.width, swapchainExt.height, generatedCount);
}

void VulkanRendererContext::generateFrameGeneration(VkCommandBuffer cb, uint32_t generation,
                                                     uint32_t targetIndex, VkImage image,
                                                     VkImageView view) {
    if (frameGenEngine == 1)
        vkr_wfg_generate_into(wfg, cb, generation, image, view, swapchainExt.width, swapchainExt.height);
    else
        vkr_lsfg_generate_into(frameGen, cb, generation, targetIndex, image, view,
                               swapchainExt.width, swapchainExt.height);
}
