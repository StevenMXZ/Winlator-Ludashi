#include "vk_dispatch.h"
#include <string.h>
VkDispatch vkd;
bool vkd_init_from_gipa(PFN_vkGetInstanceProcAddr get_instance_proc_addr) {
    memset(&vkd, 0, sizeof(vkd));
    vkd.GetInstanceProcAddr = get_instance_proc_addr;
    return get_instance_proc_addr != NULL;
}
bool vkd_load_instance(VkInstance instance) {
    if (!vkd.GetInstanceProcAddr || instance == VK_NULL_HANDLE) return false;
    #define LOAD(name) vkd.name = (PFN_vk##name)vkd.GetInstanceProcAddr(instance, "vk" #name)
    LOAD(AllocateDescriptorSets);
    LOAD(AllocateMemory);
    LOAD(BindBufferMemory);
    LOAD(BindImageMemory);
    LOAD(CmdBindDescriptorSets);
    LOAD(CmdBindPipeline);
    LOAD(CmdCopyImage);
    LOAD(CmdDispatch);
    LOAD(CmdPipelineBarrier);
    LOAD(CreateBuffer);
    LOAD(CreateComputePipelines);
    LOAD(CreateDescriptorPool);
    LOAD(CreateDescriptorSetLayout);
    LOAD(CreateImage);
    LOAD(CreateImageView);
    LOAD(CreatePipelineLayout);
    LOAD(CreateSampler);
    LOAD(CreateShaderModule);
    LOAD(DestroyBuffer);
    LOAD(DestroyDescriptorPool);
    LOAD(DestroyDescriptorSetLayout);
    LOAD(DestroyImage);
    LOAD(DestroyImageView);
    LOAD(DestroyPipeline);
    LOAD(DestroyPipelineLayout);
    LOAD(DestroySampler);
    LOAD(DestroyShaderModule);
    LOAD(FreeMemory);
    LOAD(GetBufferMemoryRequirements);
    LOAD(GetImageMemoryRequirements);
    LOAD(GetPhysicalDeviceMemoryProperties);
    LOAD(MapMemory);
    LOAD(UnmapMemory);
    LOAD(UpdateDescriptorSets);
    #undef LOAD
    return vkd.AllocateDescriptorSets && vkd.AllocateMemory && vkd.BindBufferMemory && vkd.BindImageMemory && vkd.CmdBindDescriptorSets && vkd.CmdBindPipeline && vkd.CmdCopyImage && vkd.CmdDispatch && vkd.CmdPipelineBarrier && vkd.CreateBuffer && vkd.CreateComputePipelines && vkd.CreateDescriptorPool && vkd.CreateDescriptorSetLayout && vkd.CreateImage && vkd.CreateImageView && vkd.CreatePipelineLayout && vkd.CreateSampler && vkd.CreateShaderModule && vkd.DestroyBuffer && vkd.DestroyDescriptorPool && vkd.DestroyDescriptorSetLayout && vkd.DestroyImage && vkd.DestroyImageView && vkd.DestroyPipeline && vkd.DestroyPipelineLayout && vkd.DestroySampler && vkd.DestroyShaderModule && vkd.FreeMemory && vkd.GetBufferMemoryRequirements && vkd.GetImageMemoryRequirements && vkd.GetPhysicalDeviceMemoryProperties && vkd.MapMemory && vkd.UnmapMemory && vkd.UpdateDescriptorSets;
}
void vkd_unload(void) { memset(&vkd, 0, sizeof(vkd)); }
