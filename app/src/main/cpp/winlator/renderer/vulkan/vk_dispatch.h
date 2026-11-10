// LSFG uses the same Vulkan loader/driver selected by VulkanRendererContext.
#pragma once
#include <stdbool.h>
#include <vulkan/vulkan.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct VkDispatch {
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdDispatch CmdDispatch;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkCreateComputePipelines CreateComputePipelines;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkCreateImage CreateImage;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkMapMemory MapMemory;
    PFN_vkUnmapMemory UnmapMemory;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
 } VkDispatch;
extern VkDispatch vkd;
bool vkd_init_from_gipa(PFN_vkGetInstanceProcAddr get_instance_proc_addr);
bool vkd_load_instance(VkInstance instance);
void vkd_unload(void);
#ifdef __cplusplus
}
#endif
