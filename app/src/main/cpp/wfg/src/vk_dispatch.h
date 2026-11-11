// Function-pointer dispatch for the compositor's Vulkan calls.
//
// Required because adrenotools-loaded drivers live in an isolated linker namespace and do
// not share global symbols with the system loader — every call must resolve through the
// libvulkan handle chosen at dlopen time.
//
// Init order: wfg_vkd_init(handle) -> vkCreateInstance(...) -> wfg_vkd_load_instance(instance).

#pragma once

#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES
#endif
#include <stdbool.h>
#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct WfgVkDispatch {
    // Loader-level (resolved via dlsym + vkGetInstanceProcAddr(NULL, ...))
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
    PFN_vkCreateInstance CreateInstance;
    PFN_vkEnumerateInstanceExtensionProperties EnumerateInstanceExtensionProperties;
    PFN_vkEnumerateInstanceLayerProperties EnumerateInstanceLayerProperties;

    // Instance / physical-device
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceFeatures2 GetPhysicalDeviceFeatures2;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkGetPhysicalDeviceImageFormatProperties GetPhysicalDeviceImageFormatProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties;
    PFN_vkCreateDebugUtilsMessengerEXT CreateDebugUtilsMessengerEXT;
    PFN_vkDestroyDebugUtilsMessengerEXT DestroyDebugUtilsMessengerEXT;

    // Device
    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;

    // Memory
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkUnmapMemory UnmapMemory;
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
    PFN_vkInvalidateMappedMemoryRanges InvalidateMappedMemoryRanges;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;

    // Buffer
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;

    // Image / image view
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;

    // Sampler / YCbCr
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSamplerYcbcrConversion CreateSamplerYcbcrConversion;
    PFN_vkDestroySamplerYcbcrConversion DestroySamplerYcbcrConversion;
    PFN_vkCreateSamplerYcbcrConversionKHR CreateSamplerYcbcrConversionKHR;
    PFN_vkDestroySamplerYcbcrConversionKHR DestroySamplerYcbcrConversionKHR;

    // Descriptors
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;

    // Pipeline
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkCreateComputePipelines CreateComputePipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;

    // RenderPass / Framebuffer
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;

    // Sync
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkResetFences ResetFences;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;

    // Commands
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkResetCommandPool ResetCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdBindVertexBuffers CmdBindVertexBuffers;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCmdBlitImage CmdBlitImage;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdDispatch CmdDispatch;

    // Queue
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkQueueWaitIdle QueueWaitIdle;
    PFN_vkQueuePresentKHR QueuePresentKHR;

    // Swapchain
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
} WfgVkDispatch;

extern WfgVkDispatch wfg_vkd;

bool wfg_vkd_init(void* libvulkan_handle);

// Loads device-level pointers via vkGetInstanceProcAddr too — the loader trampolines, which
// costs a few ns per call but avoids partitioning instance vs. device scope.
bool wfg_vkd_load_instance(VkInstance instance);

// Must be called before dlclose so stale-pointer crashes fault on NULL.
void wfg_vkd_unload(void);

bool wfg_vkd_bind(void* libvulkan_handle, VkInstance instance);

// For callers that already hold the driver's vkGetInstanceProcAddr rather than a dlopen handle
// (the Wayland compositor resolves Turnip through adrenotools).
bool wfg_vkd_bind_proc(PFN_vkGetInstanceProcAddr gipa, VkInstance instance);

#ifdef __cplusplus
}
#endif

// Redirect bare `vkFoo` names to the dispatch table.

#define vkGetInstanceProcAddr wfg_vkd.GetInstanceProcAddr
#define vkCreateInstance wfg_vkd.CreateInstance
#define vkEnumerateInstanceExtensionProperties wfg_vkd.EnumerateInstanceExtensionProperties
#define vkEnumerateInstanceLayerProperties wfg_vkd.EnumerateInstanceLayerProperties

#define vkDestroyInstance wfg_vkd.DestroyInstance
#define vkEnumeratePhysicalDevices wfg_vkd.EnumeratePhysicalDevices
#define vkGetPhysicalDeviceProperties wfg_vkd.GetPhysicalDeviceProperties
#define vkGetPhysicalDeviceMemoryProperties wfg_vkd.GetPhysicalDeviceMemoryProperties
#define vkGetPhysicalDeviceFeatures2 wfg_vkd.GetPhysicalDeviceFeatures2
#define vkGetPhysicalDeviceQueueFamilyProperties wfg_vkd.GetPhysicalDeviceQueueFamilyProperties
#define vkGetPhysicalDeviceFormatProperties wfg_vkd.GetPhysicalDeviceFormatProperties
#define vkGetPhysicalDeviceImageFormatProperties wfg_vkd.GetPhysicalDeviceImageFormatProperties
#define vkGetPhysicalDeviceSurfaceCapabilitiesKHR wfg_vkd.GetPhysicalDeviceSurfaceCapabilitiesKHR
#define vkGetPhysicalDeviceSurfaceFormatsKHR wfg_vkd.GetPhysicalDeviceSurfaceFormatsKHR
#define vkGetPhysicalDeviceSurfacePresentModesKHR wfg_vkd.GetPhysicalDeviceSurfacePresentModesKHR
#define vkGetPhysicalDeviceSurfaceSupportKHR wfg_vkd.GetPhysicalDeviceSurfaceSupportKHR
#define vkCreateAndroidSurfaceKHR wfg_vkd.CreateAndroidSurfaceKHR
#define vkDestroySurfaceKHR wfg_vkd.DestroySurfaceKHR
#define vkCreateDevice wfg_vkd.CreateDevice
#define vkEnumerateDeviceExtensionProperties wfg_vkd.EnumerateDeviceExtensionProperties
#define vkCreateDebugUtilsMessengerEXT wfg_vkd.CreateDebugUtilsMessengerEXT
#define vkDestroyDebugUtilsMessengerEXT wfg_vkd.DestroyDebugUtilsMessengerEXT

#define vkGetDeviceProcAddr wfg_vkd.GetDeviceProcAddr
#define vkDestroyDevice wfg_vkd.DestroyDevice
#define vkGetDeviceQueue wfg_vkd.GetDeviceQueue
#define vkDeviceWaitIdle wfg_vkd.DeviceWaitIdle

#define vkAllocateMemory wfg_vkd.AllocateMemory
#define vkFreeMemory wfg_vkd.FreeMemory
#define vkMapMemory wfg_vkd.MapMemory
#define vkUnmapMemory wfg_vkd.UnmapMemory
#define vkFlushMappedMemoryRanges wfg_vkd.FlushMappedMemoryRanges
#define vkInvalidateMappedMemoryRanges wfg_vkd.InvalidateMappedMemoryRanges
#define vkGetAndroidHardwareBufferPropertiesANDROID wfg_vkd.GetAndroidHardwareBufferPropertiesANDROID

#define vkCreateBuffer wfg_vkd.CreateBuffer
#define vkDestroyBuffer wfg_vkd.DestroyBuffer
#define vkBindBufferMemory wfg_vkd.BindBufferMemory
#define vkGetBufferMemoryRequirements wfg_vkd.GetBufferMemoryRequirements

#define vkCreateImage wfg_vkd.CreateImage
#define vkDestroyImage wfg_vkd.DestroyImage
#define vkBindImageMemory wfg_vkd.BindImageMemory
#define vkGetImageMemoryRequirements wfg_vkd.GetImageMemoryRequirements
#define vkCreateImageView wfg_vkd.CreateImageView
#define vkDestroyImageView wfg_vkd.DestroyImageView

#define vkCreateSampler wfg_vkd.CreateSampler
#define vkDestroySampler wfg_vkd.DestroySampler
#define vkCreateSamplerYcbcrConversion wfg_vkd.CreateSamplerYcbcrConversion
#define vkDestroySamplerYcbcrConversion wfg_vkd.DestroySamplerYcbcrConversion
#define vkCreateSamplerYcbcrConversionKHR wfg_vkd.CreateSamplerYcbcrConversionKHR
#define vkDestroySamplerYcbcrConversionKHR wfg_vkd.DestroySamplerYcbcrConversionKHR

#define vkCreateDescriptorSetLayout wfg_vkd.CreateDescriptorSetLayout
#define vkDestroyDescriptorSetLayout wfg_vkd.DestroyDescriptorSetLayout
#define vkCreateDescriptorPool wfg_vkd.CreateDescriptorPool
#define vkDestroyDescriptorPool wfg_vkd.DestroyDescriptorPool
#define vkAllocateDescriptorSets wfg_vkd.AllocateDescriptorSets
#define vkFreeDescriptorSets wfg_vkd.FreeDescriptorSets
#define vkUpdateDescriptorSets wfg_vkd.UpdateDescriptorSets

#define vkCreatePipelineLayout wfg_vkd.CreatePipelineLayout
#define vkDestroyPipelineLayout wfg_vkd.DestroyPipelineLayout
#define vkCreateGraphicsPipelines wfg_vkd.CreateGraphicsPipelines
#define vkDestroyPipeline wfg_vkd.DestroyPipeline
#define vkCreateShaderModule wfg_vkd.CreateShaderModule
#define vkDestroyShaderModule wfg_vkd.DestroyShaderModule

#define vkCreateRenderPass wfg_vkd.CreateRenderPass
#define vkDestroyRenderPass wfg_vkd.DestroyRenderPass
#define vkCreateFramebuffer wfg_vkd.CreateFramebuffer
#define vkDestroyFramebuffer wfg_vkd.DestroyFramebuffer

#define vkCreateFence wfg_vkd.CreateFence
#define vkDestroyFence wfg_vkd.DestroyFence
#define vkResetFences wfg_vkd.ResetFences
#define vkWaitForFences wfg_vkd.WaitForFences
#define vkCreateSemaphore wfg_vkd.CreateSemaphore
#define vkDestroySemaphore wfg_vkd.DestroySemaphore

#define vkCreateCommandPool wfg_vkd.CreateCommandPool
#define vkDestroyCommandPool wfg_vkd.DestroyCommandPool
#define vkResetCommandPool wfg_vkd.ResetCommandPool
#define vkAllocateCommandBuffers wfg_vkd.AllocateCommandBuffers
#define vkFreeCommandBuffers wfg_vkd.FreeCommandBuffers
#define vkBeginCommandBuffer wfg_vkd.BeginCommandBuffer
#define vkEndCommandBuffer wfg_vkd.EndCommandBuffer
#define vkResetCommandBuffer wfg_vkd.ResetCommandBuffer
#define vkCmdBeginRenderPass wfg_vkd.CmdBeginRenderPass
#define vkCmdEndRenderPass wfg_vkd.CmdEndRenderPass
#define vkCmdBindPipeline wfg_vkd.CmdBindPipeline
#define vkCmdBindDescriptorSets wfg_vkd.CmdBindDescriptorSets
#define vkCmdBindVertexBuffers wfg_vkd.CmdBindVertexBuffers
#define vkCmdPushConstants wfg_vkd.CmdPushConstants
#define vkCmdSetViewport wfg_vkd.CmdSetViewport
#define vkCmdSetScissor wfg_vkd.CmdSetScissor
#define vkCmdDraw wfg_vkd.CmdDraw
#define vkCmdPipelineBarrier wfg_vkd.CmdPipelineBarrier
#define vkCmdCopyBufferToImage wfg_vkd.CmdCopyBufferToImage
#define vkCmdBlitImage wfg_vkd.CmdBlitImage

#define vkQueueSubmit wfg_vkd.QueueSubmit
#define vkQueueWaitIdle wfg_vkd.QueueWaitIdle
#define vkQueuePresentKHR wfg_vkd.QueuePresentKHR

#define vkCreateSwapchainKHR wfg_vkd.CreateSwapchainKHR
#define vkDestroySwapchainKHR wfg_vkd.DestroySwapchainKHR
#define vkGetSwapchainImagesKHR wfg_vkd.GetSwapchainImagesKHR
#define vkAcquireNextImageKHR wfg_vkd.AcquireNextImageKHR
