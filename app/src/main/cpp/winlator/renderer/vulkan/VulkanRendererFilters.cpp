#include "VulkanRendererContext.h"
#include "VulkanRendererPipelineOpt.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

#include "window_vert.h"
#include "window_lanczos_frag.h"

void VulkanRendererContext::createLanczosPipeline() {
    if (lanczosPipeline != VK_NULL_HANDLE) return;

    auto vert = makeShader(window_vert_code, sizeof(window_vert_code));
    auto frag = makeShader(window_lanczos_frag_code, sizeof(window_lanczos_frag_code));

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo ia{};
    ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

    VkDynamicState dynStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dyn{};
    dyn.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dyn.dynamicStateCount = 2;
    dyn.pDynamicStates = dynStates;

    VkPipelineViewportStateCreateInfo vp{};
    vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    vp.viewportCount = 1;
    vp.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rast{};
    rast.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rast.polygonMode = VK_POLYGON_MODE_FILL;
    rast.lineWidth = 1.0f;
    rast.cullMode = VK_CULL_MODE_NONE;
    rast.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

    VkPipelineMultisampleStateCreateInfo ms{};
    ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState ba{};
    ba.colorWriteMask = 0xF;
    ba.blendEnable = VK_FALSE;

    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &ba;

    VkGraphicsPipelineCreateInfo pi{};
    pi.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount = 2;
    pi.pStages = stages;
    pi.pVertexInputState = &vi;
    pi.pInputAssemblyState = &ia;
    pi.pViewportState = &vp;
    pi.pRasterizationState = &rast;
    pi.pMultisampleState = &ms;
    pi.pColorBlendState = &blend;
    pi.pDynamicState = &dyn;
    pi.layout = pipeLayout;
    pi.renderPass = renderPass;
    pi.subpass = 0;

    VkResult result = vk_.CreateGraphicsPipelines(
        device, VK_NULL_HANDLE, 1, &pi, nullptr, &lanczosPipeline);

    vk_.DestroyShaderModule(device, frag, nullptr);
    vk_.DestroyShaderModule(device, vert, nullptr);

    if (result != VK_SUCCESS) {
        lanczosPipeline = VK_NULL_HANDLE;
        throw std::runtime_error("lanczos_pipeline");
    }
    RLOG("createLanczosPipeline: done");
}

void VulkanRendererContext::recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
    const std::vector<DrawEntry>& draws,
    std::vector<VkImageMemoryBarrier>& ahbTransitions,
    std::vector<VkImageMemoryBarrier>& preUpload,
    std::vector<VkImageMemoryBarrier>& postUpload,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short ptrX, short ptrY, short curHotX, short curHotY,
    short curW, short curH, bool curVis,
    VkRect2D scissorRect)
{
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb, &bi) != VK_SUCCESS)
        throw std::runtime_error("begin cb");

    ahbTransitions.clear();
    preUpload.clear();
    postUpload.clear();

    for (auto& d : draws) {
        if (d.img == VK_NULL_HANDLE) continue;
        if (d.isAHB && d.needsTransition) {
            VkImageMemoryBarrier b{};
            b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = d.img;
            b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            b.srcAccessMask = 0;
            b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            ahbTransitions.push_back(b);
        } else if (!d.isAHB && (d.needsTransition || d.upload != VK_NULL_HANDLE)) {
            VkImageMemoryBarrier b{};
            b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = d.img;
            b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            b.srcAccessMask = 0;
            b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            preUpload.push_back(b);

            b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            postUpload.push_back(b);
        }
    }

    if (!ahbTransitions.empty()) {
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    }
    if (!preUpload.empty()) {
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)preUpload.size(), preUpload.data());
    }

    for (auto& d : draws) {
        if (d.isAHB || d.upload == VK_NULL_HANDLE || d.img == VK_NULL_HANDLE) continue;
        VkBufferImageCopy r{};
        r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.imageExtent = {(uint32_t)d.w, (uint32_t)d.h, 1};
        vk_.CmdCopyBufferToImage(cb, d.upload, d.img,
                                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    }

    const bool cursorDrawn = curVis && cursorImg != VK_NULL_HANDLE && cursorDS != VK_NULL_HANDLE;
    const bool hasCursorCopy = hasCursorUpload && cursorImg != VK_NULL_HANDLE &&
                               cursorUpload != VK_NULL_HANDLE;
    if (hasCursorCopy) {
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        // A complete cursor upload discards the previous contents, including
        // the undefined contents of a newly created image.
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = cursorImg;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);

        VkBufferImageCopy r{};
        r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.imageExtent = {(uint32_t)curW, (uint32_t)curH, 1};
        vk_.CmdCopyBufferToImage(cb, cursorUpload, cursorImg,
                                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);

        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        postUpload.push_back(b);
    }

    if (!postUpload.empty()) {
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
            (uint32_t)postUpload.size(), postUpload.data());
    }

    VkRenderPassBeginInfo rpi{};
    rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = renderPass;
    rpi.framebuffer = swapchainFBs[imgIdx];
    rpi.renderArea = {{0, 0}, swapchainExt};
    VkClearValue clear = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    rpi.clearValueCount = 1;
    rpi.pClearValues = &clear;
    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport viewport{0, 0, (float)swapchainExt.width, (float)swapchainExt.height, 0, 1};
    vk_.CmdSetViewport(cb, 0, 1, &viewport);

    int32_t scX = std::max(0, scissorRect.offset.x);
    int32_t scY = std::max(0, scissorRect.offset.y);
    uint32_t maxW = swapchainExt.width > (uint32_t)scX ? swapchainExt.width - (uint32_t)scX : 0u;
    uint32_t maxH = swapchainExt.height > (uint32_t)scY ? swapchainExt.height - (uint32_t)scY : 0u;
    VkRect2D scissor{{scX, scY},
        {std::min(scissorRect.extent.width, maxW), std::min(scissorRect.extent.height, maxH)}};
    vk_.CmdSetScissor(cb, 0, 1, &scissor);

    float rotCosR, rotSinR;
    getPreRotationCosSin(rotCosR, rotSinR);

    const bool useSgsr = filterMode == 2 && sgsrPipeline != VK_NULL_HANDLE;
    const bool useFsr = filterMode == 3 && fsr1Pipeline != VK_NULL_HANDLE;
    const bool useLanczos = filterMode == 4 && lanczosPipeline != VK_NULL_HANDLE;
    const bool useColorBoost = filterMode == 5 && postfxPipeline != VK_NULL_HANDLE;
    const bool usePostFX = postFXMode > 0 && postfxPipeline != VK_NULL_HANDLE;
    const bool useStretch = stretchMode == 1 && stretchPipeline != VK_NULL_HANDLE;

    VkPipeline activePipeline = useSgsr ? sgsrPipeline
                              : useFsr ? fsr1Pipeline
                              : useLanczos ? lanczosPipeline
                              : useColorBoost ? postfxPipeline
                              : usePostFX ? postfxPipeline
                              : useStretch ? stretchPipeline
                              : VulkanRendererGetOpaquePipeline(this);
    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, activePipeline);

    for (auto& d : draws) {
        if (d.ds == VK_NULL_HANDLE) continue;
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout,
                                  0, 1, &d.ds, 0, nullptr);

        if (useSgsr) {
            WindowPushConstantsSGSR pc{};
            if (useStretch) { pc.ndcX0 = -1.0f; pc.ndcX1 = 1.0f; }
            else {
                pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
                pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
            }
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.useTexAlpha = 0;
            float srcW = (float)std::max(d.w, 1);
            float srcH = (float)std::max(d.h, 1);
            pc.invSrcW = 1.0f / srcW;
            pc.invSrcH = 1.0f / srcH;
            pc.srcW = srcW;
            pc.srcH = srcH;
            pc.effectId = postFXMode;
            pc.resW = 0.0f;
            pc.sharpness = sharpness;
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        } else if (useFsr || useLanczos) {
            WindowPushConstantsFSR1 pc{};
            if (useStretch) { pc.ndcX0 = -1.0f; pc.ndcX1 = 1.0f; }
            else {
                pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
                pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
            }
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.useTexAlpha = 0;
            pc.srcW = (float)std::max(d.w, 1);
            pc.srcH = (float)std::max(d.h, 1);
            pc.outW = std::max(1.0f, std::abs(pc.ndcX1 - pc.ndcX0) * 0.5f *
                                       (float)swapchainExt.width);
            pc.outH = std::max(1.0f, std::abs(pc.ndcY1 - pc.ndcY0) * 0.5f *
                                       (float)swapchainExt.height);
            pc.effectId = postFXMode;
            pc.sharpness = sharpness;
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        } else if (useColorBoost || usePostFX) {
            WindowPushConstantsPostFX pc{};
            pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.effectId = useColorBoost ? 21 : postFXMode;
            pc.sharpness = sharpness;
            pc.resW = (float)std::max(d.w, 1);
            pc.resH = (float)std::max(d.h, 1);
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        } else if (useStretch) {
            WindowPushConstantsStretch pc{};
            pc.ndcX0 = -1.0f;
            pc.ndcX1 = 1.0f;
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.useTexAlpha = 0;
            pc.strength = stretchStrength;
            pc.profile = stretchProfile;
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        } else {
            WindowPushConstants pc{};
            pc.ndcX0 = (ox + (float)d.x * sx) / cw * 2.0f - 1.0f;
            pc.ndcY0 = (oy + (float)d.y * sy) / ch * 2.0f - 1.0f;
            pc.ndcX1 = (ox + (float)(d.x + d.w) * sx) / cw * 2.0f - 1.0f;
            pc.ndcY1 = (oy + (float)(d.y + d.h) * sy) / ch * 2.0f - 1.0f;
            pc.useTexAlpha = 0;
            pc.cosR = rotCosR;
            pc.sinR = rotSinR;
            vk_.CmdPushConstants(cb, pipeLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(pc), &pc);
        }
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }

    if (cursorDrawn) {
        vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout,
                                  0, 1, &cursorDS, 0, nullptr);
        float cx = (float)std::max(0, (int)ptrX - curHotX);
        float cy = (float)std::max(0, (int)ptrY - curHotY);
        WindowPushConstants pc{};
        pc.ndcX0 = (ox + cx * sx) / cw * 2.0f - 1.0f;
        pc.ndcY0 = (oy + cy * sy) / ch * 2.0f - 1.0f;
        pc.ndcX1 = (ox + (cx + curW) * sx) / cw * 2.0f - 1.0f;
        pc.ndcY1 = (oy + (cy + curH) * sy) / ch * 2.0f - 1.0f;
        pc.useTexAlpha = 1;
        pc.cosR = rotCosR;
        pc.sinR = rotSinR;
        vk_.CmdPushConstants(cb, pipeLayout,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
            0, sizeof(pc), &pc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }

    vk_.CmdEndRenderPass(cb);

    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus != VK_SUCCESS) {
        RLOG_E("recordCmdBuf: EndCommandBuffer failed status=%d draws=%zu mode=%d",
               (int)endStatus, draws.size(), filterMode);
        throw std::runtime_error("end cb");
    }
}

