#include "VulkanRendererPipelineOpt.h"
#include "VulkanRendererContext.h"

#include <android/log.h>

#include "window_vert.h"
#include "window_frag.h"

namespace {
constexpr const char* LOG_TAG = "Winlator_Renderer";

VkShaderModule createShader(VulkanRendererContext* ctx, const uint32_t* code, size_t size) {
    VkShaderModuleCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    info.codeSize = size;
    info.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (!ctx->vk_.CreateShaderModule ||
        ctx->vk_.CreateShaderModule(ctx->device, &info, nullptr, &module) != VK_SUCCESS)
        return VK_NULL_HANDLE;
    return module;
}

VkPipeline createOpaquePipeline(VulkanRendererContext* ctx) {
    if (!ctx || ctx->device == VK_NULL_HANDLE || ctx->renderPass == VK_NULL_HANDLE ||
        ctx->pipeLayout == VK_NULL_HANDLE || !ctx->vk_.CreateGraphicsPipelines)
        return VK_NULL_HANDLE;

    VkShaderModule vert = createShader(ctx, window_vert_code, sizeof(window_vert_code));
    VkShaderModule frag = createShader(ctx, window_frag_code, sizeof(window_frag_code));
    if (vert == VK_NULL_HANDLE || frag == VK_NULL_HANDLE) {
        if (vert != VK_NULL_HANDLE) ctx->vk_.DestroyShaderModule(ctx->device, vert, nullptr);
        if (frag != VK_NULL_HANDLE) ctx->vk_.DestroyShaderModule(ctx->device, frag, nullptr);
        return VK_NULL_HANDLE;
    }

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo assembly{};
    assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

    VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{};
    dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamicStates;

    VkPipelineViewportStateCreateInfo viewport{};
    viewport.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.lineWidth = 1.0f;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

    VkPipelineMultisampleStateCreateInfo multisample{};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState attachment{};
    attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    attachment.blendEnable = VK_FALSE;

    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &attachment;

    VkGraphicsPipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = stages;
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.pInputAssemblyState = &assembly;
    pipelineInfo.pViewportState = &viewport;
    pipelineInfo.pRasterizationState = &raster;
    pipelineInfo.pMultisampleState = &multisample;
    pipelineInfo.pColorBlendState = &blend;
    pipelineInfo.pDynamicState = &dynamic;
    pipelineInfo.layout = ctx->pipeLayout;
    pipelineInfo.renderPass = ctx->renderPass;
    pipelineInfo.subpass = 0;

    VkPipeline pipeline = VK_NULL_HANDLE;
    VkResult result = ctx->vk_.CreateGraphicsPipelines(
        ctx->device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline);

    ctx->vk_.DestroyShaderModule(ctx->device, frag, nullptr);
    ctx->vk_.DestroyShaderModule(ctx->device, vert, nullptr);

    return result == VK_SUCCESS ? pipeline : VK_NULL_HANDLE;
}
}

VkPipeline VulkanRendererGetOpaquePipeline(VulkanRendererContext* ctx) {
    if (!ctx) return VK_NULL_HANDLE;

    // Called by the render thread, or teardown after the render thread joins.
    if (ctx->opaquePipeline != VK_NULL_HANDLE) return ctx->opaquePipeline;
    if (ctx->opaquePipelineAttempted) return ctx->pipeline;
    ctx->opaquePipelineAttempted = true;
    ctx->opaquePipeline = createOpaquePipeline(ctx);
    if (ctx->opaquePipeline != VK_NULL_HANDLE) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
            "RendererOpt: USING opaque window pipeline (blend OFF; cursor alpha isolated)");
        return ctx->opaquePipeline;
    }

    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
        "RendererOpt: SKIPPED opaque pipeline - falling back to alpha-blended window pipeline");
    return ctx->pipeline;
}

void VulkanRendererDestroyOpaquePipeline(VulkanRendererContext* ctx) {
    if (!ctx) return;
    if (ctx->opaquePipeline != VK_NULL_HANDLE && ctx->device != VK_NULL_HANDLE &&
        ctx->vk_.DestroyPipeline)
        ctx->vk_.DestroyPipeline(ctx->device, ctx->opaquePipeline, nullptr);
    ctx->opaquePipeline = VK_NULL_HANDLE;
    ctx->opaquePipelineAttempted = false;
}
