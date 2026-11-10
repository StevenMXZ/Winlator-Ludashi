#pragma once
#include <cstring>
#include <cstdio>
#include <iostream>
#include <stdexcept>
#include <vector>
#include "native_composition.h"
using VkCommandBuffer = uint64_t;
using VkImage = uint64_t;
using VkBuffer = uint64_t;
using VkPipeline = uint64_t;
using VkResult = int;
constexpr uint32_t MAX_FRAMES_IN_FLIGHT=2;
#define vkr_lsfg_process(...) ((void)0)
#define vkr_lsfg_generate_into(...) ((void)0)
#define RLOG_E(...) std::fprintf(stderr, __VA_ARGS__)
constexpr int VK_NULL_HANDLE=0, VK_SUCCESS=0;
enum {
    VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO=1, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
    VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO, VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT,
    VK_SUBPASS_CONTENTS_SECONDARY_COMMAND_BUFFERS, VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, VK_IMAGE_LAYOUT_UNDEFINED,
    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
    VK_IMAGE_ASPECT_COLOR_BIT, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
    VK_PIPELINE_BIND_POINT_GRAPHICS, VK_SUBPASS_CONTENTS_INLINE,
    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT=256, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT=512,
    VK_PIPELINE_STAGE_TRANSFER_BIT=1024, VK_SHADER_STAGE_VERTEX_BIT=2048, VK_SHADER_STAGE_FRAGMENT_BIT=4096
};
constexpr uint32_t VK_QUEUE_FAMILY_IGNORED=~0u;
struct VkExtent2D { uint32_t width, height; };
struct VkRect2D { struct { int32_t x,y; } offset; VkExtent2D extent; };
struct VkImageMemoryBarrier {
    int sType{}, oldLayout{}, newLayout{};
    uint32_t srcQueueFamilyIndex{}, dstQueueFamilyIndex{};
    VkImage image{};
    struct { uint32_t aspectMask, baseMipLevel, levelCount, baseArrayLayer, layerCount; } subresourceRange{};
    int srcAccessMask{}, dstAccessMask{};
};
struct VkBufferImageCopy {
    uint64_t bufferOffset{};
    uint32_t bufferRowLength{}, bufferImageHeight{};
    struct { uint32_t aspectMask, mipLevel, baseArrayLayer, layerCount; } imageSubresource{};
    struct { uint32_t width,height,depth; } imageExtent{};
};
struct VkCommandBufferInheritanceInfo { int sType{}; uint64_t renderPass{}, framebuffer{}; };
struct VkCommandBufferBeginInfo { int sType{}, flags{}; const VkCommandBufferInheritanceInfo* pInheritanceInfo{}; };
union VkClearValue { struct { float f[4]; } color; };
struct VkRenderPassBeginInfo {
    int sType{}; uint64_t renderPass{}, framebuffer{}; VkRect2D renderArea{};
    uint32_t clearValueCount{}; const VkClearValue* pClearValues{};
};
struct VkViewport { float x,y,width,height,minDepth,maxDepth; };
struct RecordingDispatch {
    uint64_t pass{}, bound{};
    std::vector<uint64_t> drawPipelines;
    std::vector<int> alpha;
    int BeginCommandBuffer(VkCommandBuffer, const VkCommandBufferBeginInfo*) { return VK_SUCCESS; }
    int EndCommandBuffer(VkCommandBuffer) { return VK_SUCCESS; }
    void ResetCommandBuffer(...) {}
    void CmdExecuteCommands(...) {}
    void CmdPipelineBarrier(...) {}
    void CmdCopyBufferToImage(...) {}
    void CmdBeginRenderPass(VkCommandBuffer, const VkRenderPassBeginInfo* info, int) { pass=info->renderPass; }
    void CmdSetViewport(...) {}
    void CmdSetScissor(...) {}
    void CmdBindPipeline(VkCommandBuffer, int, uint64_t pipeline) { bound=pipeline; }
    void CmdBindDescriptorSets(...) {}
    void CmdPushConstants(VkCommandBuffer, uint64_t, int, uint32_t, uint32_t, const void* data) {
        int value; std::memcpy(&value, static_cast<const char*>(data)+16, 4); alpha.push_back(value);
    }
    void CmdDraw(...) { drawPipelines.push_back(bound); }
    void CmdEndRenderPass(...) {}
};
class VulkanRendererContext {
public:
    struct DrawEntry { VkImage img{}; uint64_t ds{}; VkBuffer upload{}; int x{},y{},w{},h{}; bool isAHB{},needsTransition{}; };
    void beginNativeGpuTiming(VkCommandBuffer) {}
    void endNativeGpuTiming(VkCommandBuffer) {}
    RecordingDispatch vk_;
    uint64_t renderPass{1}, nativeNoClearPass{2}, pipeline{3}, opaquePipeline{4}, pipeLayout{5};
    uint64_t sgsrPipeline{6},fsr1Pipeline{7},postfxPipeline{8},stretchPipeline{9};
    uint64_t lanczosPipeline{15},frameGenRenderPass{13};
    uint64_t cursorImg{10},cursorDS{11};
    int filterMode{},postFXMode{},stretchMode{},stretchProfile{};
    float stretchStrength{},sharpness{};
    VkExtent2D swapchainExt{1280,720};
    std::vector<uint64_t> swapchainFBs{12};
    std::vector<uint64_t> swapchainImages{30},swapchainViews{31};
    struct FrameGenTarget { uint64_t image{40},view{41},framebuffer{14}; };
    std::array<FrameGenTarget,5> frameGenTargets;
    uint32_t currentFrame{};
    bool frameGenSwapchainStorage{};
    void* frameGen{};
    void transitionGeneratedSwapchainImage(...) {}
    void copyFrameGenTarget(...) {}
    bool nativeDiagnosticsEnabled{};
    bool swapRB{};
    NativeVulkanMetrics nativeMetrics;
    VkCommandBuffer nativeSceneCommand{}; bool nativeSceneReplay{};
    std::array<VkCommandBuffer,2> nativeCursorCommands{90,91};
    void getPreRotationCosSin(float& c, float& s) { c=1; s=0; }
    void recordCmdBuf(VkCommandBuffer, uint32_t, const std::vector<DrawEntry>&,
        std::vector<VkImageMemoryBarrier>&, std::vector<VkImageMemoryBarrier>&, std::vector<VkImageMemoryBarrier>&,
        VkBuffer, bool, float,float,float,float,float,float,
        short,short,short,short,short,short,bool,VkRect2D,uint32_t,bool);
};
inline VkPipeline VulkanRendererGetOpaquePipeline(VulkanRendererContext* context) {
    return context->opaquePipeline ? context->opaquePipeline : context->pipeline;
}

