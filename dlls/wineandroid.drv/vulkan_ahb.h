/*
 * Wine Vulkan WSI for Android - AHardwareBuffer swapchain
 *
 * Copyright 2024 Winlator contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

#ifndef __WINE_VULKAN_AHB_H
#define __WINE_VULKAN_AHB_H

#include <stdint.h>
#include <stdbool.h>

/* Enable Android platform Vulkan extensions */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

/* IPC message types for Wine <-> Android app communication */
#define MSG_PRESENT  1
#define MSG_RELEASE  2
#define MSG_BUFFER   3
#define MSG_REQUEST  4
/* MSG_TICK: Android → Wine. Fires from ASurfaceTransaction_setOnCommit
 * (API 31+), once per real panel vsync. Drives vkWaitForPresentKHR pacing
 * in the layer. Distinct from MSG_RELEASE because we need pacing decoupled
 * from slot-freeing: OnCommit fires ~1 vsync earlier than OnComplete, which
 * lets DXVK pipeline frames instead of being locked to apply→display
 * latency. Sent with the same struct layout as release_msg (with type=5
 * and the other fields ignored) so the receive loop can read fixed-size
 * messages. */
#define MSG_TICK     5

/* MSG_VSYNC: Android → Wine. Fires from AChoreographer_postFrameCallback
 * (API 24+), once per real panel vsync — INDEPENDENT of whether we have
 * actually applied a transaction. This is what MSG_TICK was supposed to
 * be but couldn't be (OnCommit only fires when we commit; ticks then
 * follow our own present rate, not the panel rate). MSG_VSYNC is the
 * true panel-rate signal. Used by vkWaitForPresentKHR to phase-lock the
 * layer's release of DXVK's render thread to actual hardware vsync,
 * eliminating the scheduler-jitter "doubled motion" artifact that
 * pure-wall-clock pacing exhibits during fast camera motion. Sent with
 * the same struct layout as release_msg (type=6, other fields ignored). */
#define MSG_VSYNC    6

/* Maximum swapchain images */
#define AHB_MAX_IMAGES 4

/* IPC message structures.
 *
 * IMPORTANT: layout changes here are a protocol break. Both the layer
 * (libahb_layer.so) and the Android-side native lib (libwinlator.so) MUST
 * be rebuilt and deployed together — the receiver parses fixed-size messages
 * by sizeof(struct) and any size mismatch corrupts subsequent reads. */
struct present_msg {
    uint8_t  type;         /* MSG_PRESENT */
    uint32_t slot_index;
    int32_t  acquire_fd;   /* sent as SCM_RIGHTS ancillary data */
    int32_t  dst_x, dst_y, dst_w, dst_h;
    uint64_t present_id;   /* DXVK's VkPresentIdKHR.pPresentIds[i]; 0 if none */
    uint8_t  bgra_bytes;   /* 0 = AHB memory is RGBA byte order (trojan-blit
                            *     mode — the layer's vkCmdBlitImage normalized
                            *     it).
                            * 1 = AHB memory is BGRA byte order (direct-render
                            *     mode — DXVK wrote BGRA bytes directly because
                            *     Wine's winevulkan thunk caches its own format
                            *     negotiation that bypasses the layer's
                            *     RGBA-only surface format advertisement).
                            * When 1, the receiver must perform an R↔B channel
                            * swap during composition (via vkCmdBlitImage's
                            * format-aware reinterpretation between source
                            * imported as B8G8R8A8 and destination R8G8B8A8). */
};

struct release_msg {
    uint8_t  type;         /* MSG_RELEASE / MSG_TICK / MSG_VSYNC */
    uint32_t slot_index;
    int32_t  release_fd;   /* sent as SCM_RIGHTS ancillary data */
    uint8_t  displayed;    /* 1 = onComplete release (frame was shown);
                            * 0 = mailbox-drain release (frame was skipped).
                            * Only displayed=1 advances the layer's
                            * display_count for vkWaitForPresentKHR pacing. */
    uint64_t vsync_time_ns; /* For MSG_VSYNC: AChoreographer frameTimeNanos
                             * (i.e. the actual panel vsync timestamp in
                             * CLOCK_MONOTONIC ns). Ignored for other types.
                             * Lets WFP's phase-anchored sleep target the
                             * REAL panel vsync rather than the IPC arrival
                             * time, removing socket+recv-thread latency
                             * variance from the wake target. */
};

struct buffer_msg {
    uint8_t  type;         /* MSG_BUFFER */
    uint32_t slot_index;
    /* AHardwareBuffer handle sent via AHardwareBuffer_sendHandleToUnixSocket */
};

struct request_msg {
    uint8_t  type;         /* MSG_REQUEST */
    uint32_t slot_index;
};

/* Wine VkSurfaceKHR wrapper for AHB path */
struct wine_vk_surface {
    int  socket_fd;        /* Wine end of the Unix socket pair */
    int  width;            /* Surface dimensions */
    int  height;
    bool ahb_supported;    /* VK_ANDROID_external_memory_android_hardware_buffer available */
};

/* Wine VkSwapchainKHR wrapper for AHB path */
struct wine_vk_swapchain {
    struct wine_vk_surface *surface;
    VkDevice               device;
    uint32_t               image_count;
    uint32_t               current_image;

    struct {
        VkImage         vk_image;
        VkDeviceMemory  vk_memory;
        void           *ahb;          /* AHardwareBuffer* (opaque in Wine process) */
        int             slot_index;
        VkFence         reuse_fence;   /* Fence to wait on before reusing this image */
        int             release_fence; /* Pending release fd, -1 = none */
        bool            in_use;        /* Currently acquired by the application */
    } images[AHB_MAX_IMAGES];

    /* Vulkan function pointers (device-level) */
    PFN_vkGetFenceFdKHR          pfn_vkGetFenceFdKHR;
    PFN_vkImportFenceFdKHR       pfn_vkImportFenceFdKHR;
    PFN_vkCreateFence            pfn_vkCreateFence;
    PFN_vkDestroyFence           pfn_vkDestroyFence;
    PFN_vkWaitForFences          pfn_vkWaitForFences;
    PFN_vkResetFences            pfn_vkResetFences;
    PFN_vkAllocateMemory         pfn_vkAllocateMemory;
    PFN_vkFreeMemory             pfn_vkFreeMemory;
    PFN_vkCreateImage            pfn_vkCreateImage;
    PFN_vkDestroyImage           pfn_vkDestroyImage;
    PFN_vkBindImageMemory2       pfn_vkBindImageMemory2;
    PFN_vkGetDeviceProcAddr      pfn_vkGetDeviceProcAddr;
};

/*
 * Public API for the AHB Vulkan WSI
 */

/* Create a VkSurfaceKHR backed by an AHB socket connection.
 * socket_fd: the Wine end of the Unix socket pair to the Android app.
 * width, height: surface dimensions.
 * Returns the wine_vk_surface pointer cast to VkSurfaceKHR, or VK_NULL_HANDLE on failure.
 */
VkResult wine_ahb_create_surface(VkInstance instance, int socket_fd,
                                 int width, int height,
                                 VkSurfaceKHR *out_surface);

/* Destroy a surface created by wine_ahb_create_surface. */
void wine_ahb_destroy_surface(VkSurfaceKHR surface);

/* Check if the physical device supports AHB import.
 * Returns true if VK_ANDROID_external_memory_android_hardware_buffer is available.
 */
bool wine_ahb_check_device_support(VkPhysicalDevice phys_device,
                                   PFN_vkEnumerateDeviceExtensionProperties pfn_enumerate);

/* Surface capabilities for AHB surfaces */
VkResult wine_ahb_get_surface_capabilities(struct wine_vk_surface *surface,
                                           VkSurfaceCapabilitiesKHR *caps);

/* Surface formats for AHB surfaces */
VkResult wine_ahb_get_surface_formats(uint32_t *count, VkSurfaceFormatKHR *formats);

/* Create a swapchain backed by AHardwareBuffers */
VkResult wine_ahb_create_swapchain(VkDevice device, VkPhysicalDevice phys_device,
                                   struct wine_vk_surface *surface,
                                   const VkSwapchainCreateInfoKHR *create_info,
                                   struct wine_vk_swapchain **out_swapchain,
                                   PFN_vkGetDeviceProcAddr get_device_proc_addr,
                                   PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props);

/* Destroy a swapchain */
void wine_ahb_destroy_swapchain(struct wine_vk_swapchain *swapchain);

/* Acquire the next swapchain image */
VkResult wine_ahb_acquire_next_image(struct wine_vk_swapchain *swapchain,
                                     uint64_t timeout,
                                     VkSemaphore semaphore,
                                     VkFence fence,
                                     uint32_t *image_index);

/* Get swapchain images */
VkResult wine_ahb_get_swapchain_images(struct wine_vk_swapchain *swapchain,
                                       uint32_t *count, VkImage *images);

/* Present a rendered frame.
 * present_id: DXVK's VkPresentIdKHR for this present (0 if none). Sent to
 * Android so the receiver's MSG_RELEASE callbacks can echo it back, letting
 * the layer's release-reader thread correlate releases with the specific
 * DXVK present_id that vkWaitForPresentKHR is waiting on. */
VkResult wine_ahb_queue_present(struct wine_vk_swapchain *swapchain,
                                VkQueue queue, uint32_t image_index,
                                VkFence render_fence, uint64_t present_id,
                                uint8_t bgra_bytes);

/* Import an AHardwareBuffer into a swapchain slot as a VkImage.
 *
 * vk_format selects how the AHB's bytes are interpreted by the Vulkan driver:
 *   - VK_FORMAT_R8G8B8A8_UNORM: matches the AHB's native HAL_PIXEL_FORMAT_RGBA_8888
 *     byte layout literally. Use this when a downstream vkCmdBlitImage will copy
 *     pixels from a B8G8R8A8 source — the blit's format-aware reinterpretation
 *     swaps R↔B for free (trojan-blit path).
 *   - VK_FORMAT_B8G8R8A8_UNORM: same byte layout, different label. Use this when
 *     DXVK is rendering DIRECTLY into the AHB and its swapchain format is BGRA
 *     (direct-render path). DXVK writes BGRA into the AHB's bytes; SurfaceFlinger
 *     reads them through gralloc as RGBA_8888, and because BGRA→RGBA is a
 *     well-defined no-op-on-bytes channel reinterpretation, the displayed pixels
 *     come out correct.
 *
 * Pass 0 to use the default (VK_FORMAT_R8G8B8A8_UNORM, backward compatible). */
VkResult import_ahb_to_vk_image(struct wine_vk_swapchain *swapchain,
                                uint32_t slot_index,
                                AHardwareBuffer *ahb,
                                VkPhysicalDevice phys_device,
                                PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props,
                                VkFormat vk_format);

#endif /* __WINE_VULKAN_AHB_H */
