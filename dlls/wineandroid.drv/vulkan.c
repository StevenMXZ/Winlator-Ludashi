/*
 * Wine Android driver - Vulkan surface creation dispatch
 *
 * Copyright 2024 Winlator contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This file provides the dispatch layer that routes Vulkan WSI calls
 * to either the AHB direct compositing path (vulkan_ahb.c) or the
 * existing X11 surface path, depending on device capabilities.
 */

#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include "vulkan_ahb.h"

#define LOG_TAG "Wine_Vulkan_Dispatch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Surface type tracking
 */
enum wine_surface_type {
    WINE_SURFACE_TYPE_UNKNOWN = 0,
    WINE_SURFACE_TYPE_AHB,
    WINE_SURFACE_TYPE_X11,
};

struct wine_surface_wrapper {
    enum wine_surface_type type;
    VkSurfaceKHR           handle;  /* The actual surface (wine_vk_surface* for AHB) */
};

struct wine_swapchain_wrapper {
    enum wine_surface_type       type;
    struct wine_vk_swapchain    *ahb_swapchain;  /* Non-NULL for AHB path */
    VkSwapchainKHR               x11_swapchain;  /* Non-NULL for X11 path */
};

/*
 * wine_vulkan_create_surface
 *
 * Create a Vulkan surface for the Android direct compositing path.
 * Called by the Wine Android driver when a window is created with Vulkan support.
 *
 * If the device supports VK_ANDROID_external_memory_android_hardware_buffer,
 * creates an AHB-backed surface. Otherwise, returns VK_ERROR_SURFACE_LOST_KHR
 * to signal the caller to fall back to the X11 surface path.
 */
VkResult wine_vulkan_create_surface(VkInstance instance,
                                    VkPhysicalDevice phys_device,
                                    PFN_vkEnumerateDeviceExtensionProperties pfn_enumerate,
                                    int socket_fd, int width, int height,
                                    VkSurfaceKHR *out_surface)
{
    /* Check for AHB extension support */
    if (!wine_ahb_check_device_support(phys_device, pfn_enumerate))
    {
        LOGW("wine_vulkan_create_surface: VK_ANDROID_external_memory_android_hardware_buffer "
             "not supported, falling back to X11 path");
        return VK_ERROR_SURFACE_LOST_KHR;
    }

    /* Create the AHB-backed surface */
    VkResult res = wine_ahb_create_surface(instance, socket_fd, width, height, out_surface);
    if (res != VK_SUCCESS)
    {
        LOGE("wine_vulkan_create_surface: wine_ahb_create_surface failed: %d", res);
        return res;
    }

    LOGI("wine_vulkan_create_surface: AHB surface created successfully (%dx%d)", width, height);
    return VK_SUCCESS;
}

/*
 * wine_vulkan_destroy_surface
 */
void wine_vulkan_destroy_surface(VkSurfaceKHR surface)
{
    wine_ahb_destroy_surface(surface);
}

/*
 * wine_vulkan_get_surface_capabilities
 */
VkResult wine_vulkan_get_surface_capabilities(VkSurfaceKHR vk_surface,
                                              VkSurfaceCapabilitiesKHR *caps)
{
    struct wine_vk_surface *surface = (struct wine_vk_surface *)(uintptr_t)vk_surface;
    return wine_ahb_get_surface_capabilities(surface, caps);
}

/*
 * wine_vulkan_get_surface_formats
 */
VkResult wine_vulkan_get_surface_formats(VkSurfaceKHR vk_surface,
                                         uint32_t *count,
                                         VkSurfaceFormatKHR *formats)
{
    (void)vk_surface; /* Format is fixed regardless of surface */
    return wine_ahb_get_surface_formats(count, formats);
}

/*
 * wine_vulkan_get_surface_present_modes
 *
 * AHB surfaces support FIFO (vsync) only — frame pacing is controlled by
 * SurfaceFlinger's vsync.
 */
VkResult wine_vulkan_get_surface_present_modes(VkSurfaceKHR vk_surface,
                                               uint32_t *count,
                                               VkPresentModeKHR *modes)
{
    (void)vk_surface;

    if (!count)
        return VK_ERROR_INITIALIZATION_FAILED;

    if (!modes)
    {
        *count = 1;
        return VK_SUCCESS;
    }

    if (*count < 1)
    {
        *count = 1;
        return VK_INCOMPLETE;
    }

    modes[0] = VK_PRESENT_MODE_FIFO_KHR;
    *count = 1;
    return VK_SUCCESS;
}

/*
 * wine_vulkan_create_swapchain
 */
VkResult wine_vulkan_create_swapchain(VkDevice device,
                                      VkPhysicalDevice phys_device,
                                      VkSurfaceKHR vk_surface,
                                      const VkSwapchainCreateInfoKHR *create_info,
                                      struct wine_vk_swapchain **out_swapchain,
                                      PFN_vkGetDeviceProcAddr get_device_proc_addr,
                                      PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props)
{
    struct wine_vk_surface *surface = (struct wine_vk_surface *)(uintptr_t)vk_surface;

    if (!surface->ahb_supported)
    {
        LOGE("wine_vulkan_create_swapchain: surface does not support AHB");
        return VK_ERROR_SURFACE_LOST_KHR;
    }

    return wine_ahb_create_swapchain(device, phys_device, surface, create_info,
                                     out_swapchain, get_device_proc_addr, get_mem_props);
}

/*
 * wine_vulkan_destroy_swapchain
 */
void wine_vulkan_destroy_swapchain(struct wine_vk_swapchain *swapchain)
{
    wine_ahb_destroy_swapchain(swapchain);
}

/*
 * wine_vulkan_get_swapchain_images
 */
VkResult wine_vulkan_get_swapchain_images(struct wine_vk_swapchain *swapchain,
                                          uint32_t *count, VkImage *images)
{
    return wine_ahb_get_swapchain_images(swapchain, count, images);
}

/*
 * wine_vulkan_acquire_next_image
 */
VkResult wine_vulkan_acquire_next_image(struct wine_vk_swapchain *swapchain,
                                        uint64_t timeout,
                                        VkSemaphore semaphore,
                                        VkFence fence,
                                        uint32_t *image_index)
{
    return wine_ahb_acquire_next_image(swapchain, timeout, semaphore, fence, image_index);
}

/*
 * wine_vulkan_queue_present
 */
VkResult wine_vulkan_queue_present(struct wine_vk_swapchain *swapchain,
                                   VkQueue queue, uint32_t image_index,
                                   VkFence render_fence)
{
    /* present_id=0 — this path doesn't know about DXVK's VkPresentIdKHR.
     * Only the layer's intercepted vkQueuePresentKHR extracts the real id.
     * bgra_bytes=0 — legacy non-layer path uses the trojan-blit byte layout. */
    return wine_ahb_queue_present(swapchain, queue, image_index, render_fence, 0, 0);
}
