#pragma once
#include <cstdint>
#include <vulkan/vulkan.h>

// Frame generation layer for the Vulkan renderer.
//
// The renderer composes the X11 scene into an offscreen image instead of the
// swapchain, estimates motion between the last two real frames on the GPU and
// presents interpolated frames in between. Everything lives in this folder;
// the renderer only calls the hooks below.

class VulkanRendererContext;
struct FrameGenState;

// Redirects VulkanRendererContext::recordCmdBuf. A default-constructed value
// (or a null pointer) leaves the normal swapchain path untouched.
struct FrameGenPass {
    VkRenderPass  renderPass  = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    // Draw the pre-composed scene 1:1 (no filters) and add the cursor on top.
    bool          compose     = false;
};

struct FrameGenStats {
    float srcFps = 0.f;
    float outFps = 0.f;
    float gpuMs = 0.f;
    float addedLatencyMs = 0.f;
    int   generated = 0;  // generated frames per real frame
    int   state = 0;      // 0 off, 1 active, 2 unavailable, 3 paused (GPU too slow),
                          // 4 active with motion quality lowered for the GPU
    int   quality = 0;    // motion quality in use
};

// Called from renderFrame. Returns true when the frame was fully handled.
bool VulkanRendererFrameGenRender(VulkanRendererContext* ctx);

// Called whenever a window receives new pixels.
void VulkanRendererFrameGenNoteContent(VulkanRendererContext* ctx, int width, int height);

// multiplier: output frames per real frame in power saver mode (2 or 3).
void VulkanRendererFrameGenConfigure(VulkanRendererContext* ctx, bool enabled, int mode,
                                     int quality, int multiplier, float targetHz);
FrameGenStats VulkanRendererFrameGenGetStats(VulkanRendererContext* ctx);

// Display vsync timestamp (CLOCK_MONOTONIC ns, as given by Choreographer).
void VulkanRendererFrameGenNoteVsync(int64_t frameTimeNs);

// Power saver X3 lifts its source cap while the image is still (loading screens).
bool VulkanRendererFrameGenSourceUncapped();

// Must run before the device is destroyed.
void VulkanRendererFrameGenDestroy(VulkanRendererContext* ctx);
