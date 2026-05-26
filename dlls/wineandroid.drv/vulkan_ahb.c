/*
 * Wine Vulkan WSI for Android - AHardwareBuffer swapchain implementation
 *
 * Copyright 2024 Winlator contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/hardware_buffer.h>
#include <android/log.h>

#include "vulkan_ahb.h"

#define LOG_TAG "Wine_AHB_WSI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Timeout for fence wait in nanoseconds (100ms) */
#define FENCE_WAIT_TIMEOUT_NS 100000000ULL

/*
 * Helper: send a file descriptor over a Unix socket using SCM_RIGHTS
 */
static int send_fd(int socket, const void *msg_data, size_t msg_len, int fd)
{
    struct msghdr msg = {0};
    struct iovec iov;
    char cmsg_buf[CMSG_SPACE(sizeof(int))];

    iov.iov_base = (void *)msg_data;
    iov.iov_len = msg_len;
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    if (fd >= 0)
    {
        msg.msg_control = cmsg_buf;
        msg.msg_controllen = sizeof(cmsg_buf);

        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));
    }

    ssize_t ret = sendmsg(socket, &msg, 0);
    if (ret < 0)
    {
        LOGE("send_fd: sendmsg failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

/*
 * Helper: receive a file descriptor from a Unix socket using SCM_RIGHTS
 */
static int recv_fd(int socket, void *msg_data, size_t msg_len, int *out_fd)
{
    struct msghdr msg = {0};
    struct iovec iov;
    char cmsg_buf[CMSG_SPACE(sizeof(int))];

    iov.iov_base = msg_data;
    iov.iov_len = msg_len;
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf;
    msg.msg_controllen = sizeof(cmsg_buf);

    ssize_t ret = recvmsg(socket, &msg, 0);
    if (ret < 0)
    {
        LOGE("recv_fd: recvmsg failed: %s", strerror(errno));
        return -1;
    }
    if (ret == 0)
    {
        LOGE("recv_fd: socket disconnected");
        return -1;
    }

    *out_fd = -1;
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS)
    {
        memcpy(out_fd, CMSG_DATA(cmsg), sizeof(int));
    }

    return 0;
}

/*
 * Helper: send a simple message without an fd
 */
static int send_msg(int socket, const void *msg_data, size_t msg_len)
{
    ssize_t ret = send(socket, msg_data, msg_len, 0);
    if (ret < 0)
    {
        LOGE("send_msg: send failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

/*
 * Helper: receive a message (blocking)
 */
static int recv_msg(int socket, void *msg_data, size_t msg_len)
{
    ssize_t total = 0;
    while ((size_t)total < msg_len)
    {
        ssize_t ret = recv(socket, (char *)msg_data + total, msg_len - total, 0);
        if (ret < 0)
        {
            if (errno == EINTR) continue;
            LOGE("recv_msg: recv failed: %s", strerror(errno));
            return -1;
        }
        if (ret == 0)
        {
            LOGE("recv_msg: socket disconnected");
            return -1;
        }
        total += ret;
    }
    return 0;
}

/*
 * Helper: poll for a release message (non-blocking)
 * Returns 1 if a release message was received, 0 if none available, -1 on error.
 */
static int poll_release_msg(int socket, struct release_msg *out)
{
    struct msghdr msg = {0};
    struct iovec iov;
    char cmsg_buf[CMSG_SPACE(sizeof(int))];

    iov.iov_base = out;
    iov.iov_len = sizeof(*out);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf;
    msg.msg_controllen = sizeof(cmsg_buf);

    ssize_t ret = recvmsg(socket, &msg, MSG_DONTWAIT);
    if (ret < 0)
    {
        if (errno == EAGAIN || errno == EWOULDBLOCK)
            return 0; /* No message available */
        LOGE("poll_release_msg: recvmsg failed: %s", strerror(errno));
        return -1;
    }
    if (ret == 0)
    {
        LOGE("poll_release_msg: socket disconnected");
        return -1;
    }

    out->release_fd = -1;
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS)
    {
        memcpy(&out->release_fd, CMSG_DATA(cmsg), sizeof(int));
    }

    return 1;
}

/*
 * wine_ahb_check_device_support
 *
 * Check if the physical device supports VK_ANDROID_external_memory_android_hardware_buffer.
 */
bool wine_ahb_check_device_support(VkPhysicalDevice phys_device,
                                   PFN_vkEnumerateDeviceExtensionProperties pfn_enumerate)
{
    uint32_t ext_count = 0;
    VkResult res;

    if (!pfn_enumerate)
        return false;

    res = pfn_enumerate(phys_device, NULL, &ext_count, NULL);
    if (res != VK_SUCCESS || ext_count == 0)
        return false;

    VkExtensionProperties *exts = calloc(ext_count, sizeof(VkExtensionProperties));
    if (!exts)
        return false;

    res = pfn_enumerate(phys_device, NULL, &ext_count, exts);
    if (res != VK_SUCCESS)
    {
        free(exts);
        return false;
    }

    bool found = false;
    for (uint32_t i = 0; i < ext_count; i++)
    {
        if (strcmp(exts[i].extensionName,
                  VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) == 0)
        {
            found = true;
            break;
        }
    }

    free(exts);
    return found;
}

/*
 * wine_ahb_create_surface
 *
 * Create a VkSurfaceKHR backed by an AHB socket connection.
 */
VkResult wine_ahb_create_surface(VkInstance instance, int socket_fd,
                                 int width, int height,
                                 VkSurfaceKHR *out_surface)
{
    struct wine_vk_surface *surface;

    if (socket_fd < 0 || width <= 0 || height <= 0)
    {
        LOGE("wine_ahb_create_surface: invalid parameters (fd=%d, %dx%d)",
             socket_fd, width, height);
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    surface = calloc(1, sizeof(*surface));
    if (!surface)
    {
        LOGE("wine_ahb_create_surface: allocation failed");
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }

    surface->socket_fd = socket_fd;
    surface->width = width;
    surface->height = height;
    surface->ahb_supported = true; /* Will be validated at swapchain creation */

    *out_surface = (VkSurfaceKHR)(uintptr_t)surface;

    LOGI("wine_ahb_create_surface: created surface %dx%d on fd=%d", width, height, socket_fd);
    return VK_SUCCESS;
}

/*
 * wine_ahb_destroy_surface
 */
void wine_ahb_destroy_surface(VkSurfaceKHR vk_surface)
{
    struct wine_vk_surface *surface = (struct wine_vk_surface *)(uintptr_t)vk_surface;
    if (!surface)
        return;

    if (surface->socket_fd >= 0)
    {
        close(surface->socket_fd);
        surface->socket_fd = -1;
    }

    free(surface);
    LOGI("wine_ahb_destroy_surface: surface destroyed");
}

/*
 * wine_ahb_get_surface_capabilities
 *
 * Report surface capabilities: minImageCount=2, maxImageCount=3,
 * currentExtent = screen dimensions.
 */
VkResult wine_ahb_get_surface_capabilities(struct wine_vk_surface *surface,
                                           VkSurfaceCapabilitiesKHR *caps)
{
    if (!surface || !caps)
        return VK_ERROR_INITIALIZATION_FAILED;

    memset(caps, 0, sizeof(*caps));

    caps->minImageCount = 2;
    caps->maxImageCount = 3;
    caps->currentExtent.width = (uint32_t)surface->width;
    caps->currentExtent.height = (uint32_t)surface->height;
    caps->minImageExtent = caps->currentExtent;
    caps->maxImageExtent = caps->currentExtent;
    caps->maxImageArrayLayers = 1;
    caps->supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    caps->supportedUsageFlags = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                              | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                              | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    return VK_SUCCESS;
}

/*
 * wine_ahb_get_surface_formats
 *
 * Return exactly VK_FORMAT_R8G8B8A8_UNORM / VK_COLOR_SPACE_SRGB_NONLINEAR_KHR.
 */
VkResult wine_ahb_get_surface_formats(uint32_t *count, VkSurfaceFormatKHR *formats)
{
    if (!count)
        return VK_ERROR_INITIALIZATION_FAILED;

    if (!formats)
    {
        *count = 1;
        return VK_SUCCESS;
    }

    if (*count < 1)
    {
        *count = 1;
        return VK_INCOMPLETE;
    }

    formats[0].format = VK_FORMAT_R8G8B8A8_UNORM;
    formats[0].colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    *count = 1;

    return VK_SUCCESS;
}


/*
 * Helper: import an AHardwareBuffer into Vulkan as a VkImage
 */
VkResult import_ahb_to_vk_image(struct wine_vk_swapchain *swapchain,
                                       uint32_t slot_index,
                                       AHardwareBuffer *ahb,
                                       VkPhysicalDevice phys_device,
                                       PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props,
                                       VkFormat vk_format)
{
    VkDevice device = swapchain->device;
    int width = swapchain->surface->width;
    int height = swapchain->surface->height;
    VkResult res;

    /* Default: legacy trojan-blit path used R8G8B8A8 because the format-aware
     * blit from a B8G8R8A8 trojan handled the byte swap. */
    if (vk_format == 0) vk_format = VK_FORMAT_R8G8B8A8_UNORM;

    /* Create the VkImage with external memory info */
    VkExternalMemoryImageCreateInfo ext_mem_info = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .pNext = NULL,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };

    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &ext_mem_info,
        .flags = 0,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = vk_format,
        .extent = { (uint32_t)width, (uint32_t)height, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
               | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
               | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };

    res = swapchain->pfn_vkCreateImage(device, &image_info, NULL,
                                       &swapchain->images[slot_index].vk_image);
    if (res != VK_SUCCESS)
    {
        LOGE("import_ahb_to_vk_image: vkCreateImage failed for slot %u: %d",
             slot_index, res);
        return res;
    }

    /* Import the AHardwareBuffer as device memory */
    VkImportAndroidHardwareBufferInfoANDROID import_ahb_info = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .pNext = NULL,
        .buffer = ahb,
    };

    VkMemoryDedicatedAllocateInfo dedicated_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = &import_ahb_info,
        .image = swapchain->images[slot_index].vk_image,
        .buffer = VK_NULL_HANDLE,
    };

    /* Get memory requirements to find the right memory type */
    VkPhysicalDeviceMemoryProperties mem_props;
    get_mem_props(phys_device, &mem_props);

    /* For AHB imports, we typically use memory type 0 or find one that supports
     * the required memory type bits. The actual memory type is determined by
     * the AHB allocation, so we use the first available type. */
    VkMemoryAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &dedicated_info,
        .allocationSize = 0, /* Ignored for AHB imports */
        .memoryTypeIndex = 0,
    };

    /* Find a suitable memory type for device-local memory */
    for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++)
    {
        if (mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
        {
            alloc_info.memoryTypeIndex = i;
            break;
        }
    }

    res = swapchain->pfn_vkAllocateMemory(device, &alloc_info, NULL,
                                          &swapchain->images[slot_index].vk_memory);
    if (res != VK_SUCCESS)
    {
        LOGE("import_ahb_to_vk_image: vkAllocateMemory failed for slot %u: %d",
             slot_index, res);
        swapchain->pfn_vkDestroyImage(device, swapchain->images[slot_index].vk_image, NULL);
        swapchain->images[slot_index].vk_image = VK_NULL_HANDLE;
        return res;
    }

    /* Bind the imported memory to the image */
    VkBindImageMemoryInfo bind_info = {
        .sType = VK_STRUCTURE_TYPE_BIND_IMAGE_MEMORY_INFO,
        .pNext = NULL,
        .image = swapchain->images[slot_index].vk_image,
        .memory = swapchain->images[slot_index].vk_memory,
        .memoryOffset = 0,
    };

    res = swapchain->pfn_vkBindImageMemory2(device, 1, &bind_info);
    if (res != VK_SUCCESS)
    {
        LOGE("import_ahb_to_vk_image: vkBindImageMemory2 failed for slot %u: %d",
             slot_index, res);
        swapchain->pfn_vkFreeMemory(device, swapchain->images[slot_index].vk_memory, NULL);
        swapchain->pfn_vkDestroyImage(device, swapchain->images[slot_index].vk_image, NULL);
        swapchain->images[slot_index].vk_image = VK_NULL_HANDLE;
        swapchain->images[slot_index].vk_memory = VK_NULL_HANDLE;
        return res;
    }

    swapchain->images[slot_index].ahb = ahb;
    swapchain->images[slot_index].slot_index = (int)slot_index;

    LOGI("import_ahb_to_vk_image: slot %u imported successfully", slot_index);
    return VK_SUCCESS;
}

/*
 * wine_ahb_create_swapchain
 *
 * Create a swapchain backed by AHardwareBuffers received from the Android app.
 * For each image slot:
 *   1. Send a "request buffer" message to the Android app via socket.
 *   2. Receive the AHardwareBuffer handle via AHardwareBuffer_recvHandleFromUnixSocket.
 *   3. Import the AHB into Vulkan as a VkImage using VkImportAndroidHardwareBufferInfoANDROID.
 */
VkResult wine_ahb_create_swapchain(VkDevice device, VkPhysicalDevice phys_device,
                                   struct wine_vk_surface *surface,
                                   const VkSwapchainCreateInfoKHR *create_info,
                                   struct wine_vk_swapchain **out_swapchain,
                                   PFN_vkGetDeviceProcAddr get_device_proc_addr,
                                   PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props)
{
    struct wine_vk_swapchain *swapchain;
    uint32_t image_count;
    VkResult res;

    if (!surface || !surface->ahb_supported)
    {
        LOGE("wine_ahb_create_swapchain: surface not supported for AHB");
        return VK_ERROR_SURFACE_LOST_KHR;
    }

    /* Determine image count (clamp to [2, 3]) */
    image_count = create_info->minImageCount;
    if (image_count < 2) image_count = 2;
    if (image_count > AHB_MAX_IMAGES) image_count = AHB_MAX_IMAGES;

    swapchain = calloc(1, sizeof(*swapchain));
    if (!swapchain)
        return VK_ERROR_OUT_OF_HOST_MEMORY;

    swapchain->surface = surface;
    swapchain->device = device;
    swapchain->image_count = image_count;
    swapchain->current_image = 0;

    /* Load device-level function pointers */
    swapchain->pfn_vkGetFenceFdKHR = (PFN_vkGetFenceFdKHR)
        get_device_proc_addr(device, "vkGetFenceFdKHR");
    swapchain->pfn_vkImportFenceFdKHR = (PFN_vkImportFenceFdKHR)
        get_device_proc_addr(device, "vkImportFenceFdKHR");
    swapchain->pfn_vkCreateFence = (PFN_vkCreateFence)
        get_device_proc_addr(device, "vkCreateFence");
    swapchain->pfn_vkDestroyFence = (PFN_vkDestroyFence)
        get_device_proc_addr(device, "vkDestroyFence");
    swapchain->pfn_vkWaitForFences = (PFN_vkWaitForFences)
        get_device_proc_addr(device, "vkWaitForFences");
    swapchain->pfn_vkResetFences = (PFN_vkResetFences)
        get_device_proc_addr(device, "vkResetFences");
    swapchain->pfn_vkAllocateMemory = (PFN_vkAllocateMemory)
        get_device_proc_addr(device, "vkAllocateMemory");
    swapchain->pfn_vkFreeMemory = (PFN_vkFreeMemory)
        get_device_proc_addr(device, "vkFreeMemory");
    swapchain->pfn_vkCreateImage = (PFN_vkCreateImage)
        get_device_proc_addr(device, "vkCreateImage");
    swapchain->pfn_vkDestroyImage = (PFN_vkDestroyImage)
        get_device_proc_addr(device, "vkDestroyImage");
    swapchain->pfn_vkBindImageMemory2 = (PFN_vkBindImageMemory2)
        get_device_proc_addr(device, "vkBindImageMemory2");
    swapchain->pfn_vkGetDeviceProcAddr = get_device_proc_addr;

    if (!swapchain->pfn_vkGetFenceFdKHR || !swapchain->pfn_vkImportFenceFdKHR)
    {
        LOGE("wine_ahb_create_swapchain: VK_KHR_external_fence_fd not available");
        free(swapchain);
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    /* Create reuse fences for each image slot */
    VkFenceCreateInfo fence_info = {
        .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
        .pNext = NULL,
        .flags = VK_FENCE_CREATE_SIGNALED_BIT, /* Start signaled so first acquire succeeds */
    };

    for (uint32_t i = 0; i < image_count; i++)
    {
        res = swapchain->pfn_vkCreateFence(device, &fence_info, NULL,
                                           &swapchain->images[i].reuse_fence);
        if (res != VK_SUCCESS)
        {
            LOGE("wine_ahb_create_swapchain: vkCreateFence failed for slot %u: %d", i, res);
            goto cleanup;
        }
        swapchain->images[i].release_fence = -1;
        swapchain->images[i].in_use = false;
    }

    /* Request and receive AHardwareBuffers from the Android app */
    for (uint32_t i = 0; i < image_count; i++)
    {
        struct request_msg req = {
            .type = MSG_REQUEST,
            .slot_index = i,
        };

        /* Send request for buffer at slot i */
        if (send_msg(surface->socket_fd, &req, sizeof(req)) < 0)
        {
            LOGE("wine_ahb_create_swapchain: failed to send buffer request for slot %u", i);
            res = VK_ERROR_INITIALIZATION_FAILED;
            goto cleanup;
        }

        /* Receive the AHardwareBuffer handle from the Android app */
        AHardwareBuffer *ahb = NULL;
        int recv_result = AHardwareBuffer_recvHandleFromUnixSocket(surface->socket_fd, &ahb);
        if (recv_result != 0 || !ahb)
        {
            LOGE("wine_ahb_create_swapchain: AHardwareBuffer_recvHandleFromUnixSocket "
                 "failed for slot %u: %d", i, recv_result);
            res = VK_ERROR_INITIALIZATION_FAILED;
            goto cleanup;
        }

        /* Import the AHB into Vulkan */
        /* Default format (0 -> R8G8B8A8): legacy wine_ahb_create_swapchain path
         * doesn't have a layer above it to choose a different format. */
        res = import_ahb_to_vk_image(swapchain, i, ahb, phys_device, get_mem_props, 0);
        if (res != VK_SUCCESS)
        {
            AHardwareBuffer_release(ahb);
            goto cleanup;
        }

        LOGI("wine_ahb_create_swapchain: slot %u buffer received and imported", i);
    }

    *out_swapchain = swapchain;
    LOGI("wine_ahb_create_swapchain: created swapchain with %u images (%dx%d)",
         image_count, surface->width, surface->height);
    return VK_SUCCESS;

cleanup:
    for (uint32_t i = 0; i < image_count; i++)
    {
        if (swapchain->images[i].vk_image != VK_NULL_HANDLE)
        {
            swapchain->pfn_vkDestroyImage(device, swapchain->images[i].vk_image, NULL);
        }
        if (swapchain->images[i].vk_memory != VK_NULL_HANDLE)
        {
            swapchain->pfn_vkFreeMemory(device, swapchain->images[i].vk_memory, NULL);
        }
        if (swapchain->images[i].ahb)
        {
            AHardwareBuffer_release((AHardwareBuffer *)swapchain->images[i].ahb);
        }
        if (swapchain->images[i].reuse_fence != VK_NULL_HANDLE)
        {
            swapchain->pfn_vkDestroyFence(device, swapchain->images[i].reuse_fence, NULL);
        }
    }
    free(swapchain);
    return res;
}

/*
 * wine_ahb_destroy_swapchain
 */
void wine_ahb_destroy_swapchain(struct wine_vk_swapchain *swapchain)
{
    if (!swapchain)
        return;

    VkDevice device = swapchain->device;

    /* Iterate ALL AHB_MAX_IMAGES slots (not just image_count). The layer may
     * cap swapchain->image_count below the originally-imported count when
     * the real trojan swapchain has fewer images than the AHB pool — slots
     * [image_count..AHB_MAX_IMAGES-1] still hold valid VkImage/VkMemory
     * resources that must be freed. NULL checks below make this safe for
     * un-imported slots. */
    for (uint32_t i = 0; i < AHB_MAX_IMAGES; i++)
    {
        /* Wait for any pending reuse fence before destroying */
        if (swapchain->images[i].reuse_fence != VK_NULL_HANDLE)
        {
            swapchain->pfn_vkWaitForFences(device, 1, &swapchain->images[i].reuse_fence,
                                           VK_TRUE, FENCE_WAIT_TIMEOUT_NS);
            swapchain->pfn_vkDestroyFence(device, swapchain->images[i].reuse_fence, NULL);
        }

        if (swapchain->images[i].vk_image != VK_NULL_HANDLE)
            swapchain->pfn_vkDestroyImage(device, swapchain->images[i].vk_image, NULL);

        if (swapchain->images[i].vk_memory != VK_NULL_HANDLE)
            swapchain->pfn_vkFreeMemory(device, swapchain->images[i].vk_memory, NULL);

        /* NOTE: Do NOT release the AHB here. The AHardwareBuffer is owned by
         * the Android-side AHardwareBufferPool. Wine received a handle via
         * recvHandleFromUnixSocket which gives us a reference, but releasing it
         * here would decrement the refcount and potentially invalidate the buffer
         * while the Android present-receiver thread is still using it.
         * The AHBs stay alive for the lifetime of the pool (managed by
         * DirectCompositorComponent on the Android side).
         *
         * DXVK commonly destroys and recreates swapchains (e.g., on resize or
         * mode change). The AHBs must remain valid across swapchain recreation. */
        /* if (swapchain->images[i].ahb)
            AHardwareBuffer_release((AHardwareBuffer *)swapchain->images[i].ahb); */

        if (swapchain->images[i].release_fence >= 0)
            close(swapchain->images[i].release_fence);
    }

    free(swapchain);
    LOGI("wine_ahb_destroy_swapchain: swapchain destroyed (AHBs preserved)");
}

/*
 * wine_ahb_get_swapchain_images
 */
VkResult wine_ahb_get_swapchain_images(struct wine_vk_swapchain *swapchain,
                                       uint32_t *count, VkImage *images)
{
    if (!swapchain || !count)
        return VK_ERROR_INITIALIZATION_FAILED;

    if (!images)
    {
        *count = swapchain->image_count;
        return VK_SUCCESS;
    }

    uint32_t to_copy = *count < swapchain->image_count ? *count : swapchain->image_count;
    for (uint32_t i = 0; i < to_copy; i++)
    {
        images[i] = swapchain->images[i].vk_image;
    }
    *count = to_copy;

    return (to_copy < swapchain->image_count) ? VK_INCOMPLETE : VK_SUCCESS;
}

/*
 * wine_ahb_acquire_next_image
 *
 * Acquire the next available swapchain image. For the MVP without release
 * fences, this does simple round-robin without blocking.
 */
VkResult wine_ahb_acquire_next_image(struct wine_vk_swapchain *swapchain,
                                     uint64_t timeout,
                                     VkSemaphore semaphore,
                                     VkFence fence,
                                     uint32_t *image_index)
{
    if (!swapchain || !image_index)
        return VK_ERROR_INITIALIZATION_FAILED;

    (void)timeout;
    (void)semaphore;
    (void)fence;

    /* Process any pending release messages from the Android app */
    struct release_msg rel;
    while (poll_release_msg(swapchain->surface->socket_fd, &rel) > 0)
    {
        if (rel.type == MSG_RELEASE && rel.slot_index < swapchain->image_count)
        {
            uint32_t slot = rel.slot_index;
            swapchain->images[slot].in_use = false;

            if (rel.release_fd >= 0)
            {
                close(rel.release_fd); /* MVP: just close it */
            }
        }
    }

    /* Simple round-robin — always return the next slot.
     * For the MVP, we don't block on fences since the Android side
     * doesn't send release messages yet. The AHBs are triple-buffered
     * so tearing is acceptable for now. */
    uint32_t idx = swapchain->current_image;
    swapchain->current_image = (idx + 1) % swapchain->image_count;
    swapchain->images[idx].in_use = true;
    *image_index = idx;

    return VK_SUCCESS;
}

/*
 * wine_ahb_queue_present
 *
 * Present a rendered frame:
 * 1. Export render-complete fence as sync fd via vkGetFenceFdKHR
 * 2. Send present_msg{slot_index, acquire_fd} to Android app via socket
 * 3. The Android app will asynchronously send back release_msg when done
 */
VkResult wine_ahb_queue_present(struct wine_vk_swapchain *swapchain,
                                VkQueue queue, uint32_t image_index,
                                VkFence render_fence, uint64_t present_id,
                                uint8_t bgra_bytes)
{
    if (!swapchain || image_index >= swapchain->image_count)
        return VK_ERROR_INITIALIZATION_FAILED;

    struct wine_vk_surface *surface = swapchain->surface;
    int acquire_fd = -1;

    /* Step 1: Export the render-complete fence as a sync fd */
    if (render_fence != VK_NULL_HANDLE && swapchain->pfn_vkGetFenceFdKHR)
    {
        VkFenceGetFdInfoKHR get_fd_info = {
            .sType = VK_STRUCTURE_TYPE_FENCE_GET_FD_INFO_KHR,
            .pNext = NULL,
            .fence = render_fence,
            .handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT,
        };

        VkResult fd_res = swapchain->pfn_vkGetFenceFdKHR(swapchain->device,
                                                          &get_fd_info, &acquire_fd);
        if (fd_res != VK_SUCCESS)
        {
            LOGW("wine_ahb_queue_present: vkGetFenceFdKHR failed: %d, presenting without fence",
                 fd_res);
            acquire_fd = -1;
        }
    }

    /* Step 2: Send present_msg to the Android app with the acquire fence fd */
    struct present_msg pmsg = {
        .type = MSG_PRESENT,
        .slot_index = image_index,
        .acquire_fd = acquire_fd,
        .dst_x = 0,
        .dst_y = 0,
        .dst_w = surface->width,
        .dst_h = surface->height,
        .present_id = present_id,
        .bgra_bytes = bgra_bytes,
    };

    int send_result;
    if (acquire_fd >= 0)
    {
        /* Send message with fd as ancillary data */
        send_result = send_fd(surface->socket_fd, &pmsg, sizeof(pmsg), acquire_fd);
        /* Ownership of acquire_fd transfers to the receiver; close our copy */
        close(acquire_fd);
    }
    else
    {
        /* No fence to send */
        send_result = send_msg(surface->socket_fd, &pmsg, sizeof(pmsg));
    }

    if (send_result < 0)
    {
        LOGE("wine_ahb_queue_present: failed to send present message for slot %u",
             image_index);
        return VK_ERROR_SURFACE_LOST_KHR;
    }

    /* Mark the image as no longer in-use so the next acquire can reuse it.
     * In the MVP without release fences, we immediately allow reuse.
     * This may cause tearing but ensures the swapchain doesn't stall. */
    swapchain->images[image_index].in_use = false;

    return VK_SUCCESS;
}
