#pragma once
#include <vulkan/vulkan.h>
#include <cstdint>

struct VkrWfg;
bool vkr_wfg_bind_dispatch(PFN_vkGetInstanceProcAddr gipa, VkInstance instance);
VkrWfg* vkr_wfg_create(VkDevice device, VkPhysicalDevice physicalDevice);
void vkr_wfg_destroy(VkrWfg* engine);
void vkr_wfg_configure(VkrWfg* engine, uint32_t multiplier, uint32_t targetRate,
                        uint32_t quality, float refreshRate);
bool vkr_wfg_prepare(VkrWfg* engine, uint32_t width, uint32_t height, VkFormat format);
uint32_t vkr_wfg_plan(VkrWfg* engine, uint32_t capacity, uint64_t sourceFrames,
                        float refreshRate);
bool vkr_wfg_needs_history(const VkrWfg* engine);
void vkr_wfg_process(VkrWfg* engine, VkCommandBuffer cmd, VkImage source,
                      uint32_t width, uint32_t height, uint32_t generations);
void vkr_wfg_generate_into(VkrWfg* engine, VkCommandBuffer cmd, uint32_t generation,
                            VkImage target, VkImageView view, uint32_t width, uint32_t height);
void vkr_wfg_forget_targets(VkrWfg* engine);
