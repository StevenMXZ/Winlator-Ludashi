#pragma once

#include <vector>
#include <unordered_map>
#include <unordered_set>

#include "drawable.hpp"
#include "renderer_jni.hpp"
#include "vulkan.hpp"

class EffectComposer {
    private:
        struct PushConstants {
            int width;
            int height;
        };
        
        struct DescriptorPool {
            VkDescriptorPool handle;
            uint32_t maxSize;
            std::unordered_set<VkImage> bindedImages;
            
            bool isFull() {
                return bindedImages.size() >= maxSize;
            }
            
            void addBindedImage(VkImage image) {
                bindedImages.insert(image);
            }
            
            void removeBindedImage(VkImage image) {
                bindedImages.erase(image);
            }
        };
        
        class DescriptorPoolBuffer {
            private: 
                std::vector<std::shared_ptr<DescriptorPool>> pools;
                std::unordered_map<VkDescriptorSet, DescriptorPool *> bindedSets;
            
            public:
                void addNew(VkDevice device) {
                    auto descriptorPool = std::make_shared<DescriptorPool>();
                    descriptorPool->maxSize = 1024;
                
                    VkDescriptorPoolSize descSizes[2];
                    descSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                    descSizes[0].descriptorCount = descriptorPool->maxSize;
                    descSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                    descSizes[1].descriptorCount = descriptorPool->maxSize;
                
                    VkDescriptorPoolCreateInfo createInfo{};
                    createInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
                    createInfo.pNext = nullptr;
                    createInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
                    createInfo.maxSets = descriptorPool->maxSize;
                    createInfo.poolSizeCount = 2;
                    createInfo.pPoolSizes = descSizes;
                
                    vkCreateDescriptorPool(device, &createInfo, nullptr, &descriptorPool->handle);
                
                    pools.push_back(descriptorPool);
                }
            
                DescriptorPool *findFreePool(VkDevice device) {
                    for (const auto& pool : pools) {
                        if (!pool->isFull()) 
                            return pool.get();
                    }
               
                    addNew(device);
                    return pools.back().get();
                }
                
                DescriptorPool *getPoolForSet(VkDescriptorSet descriptorSet) {
                    auto it = bindedSets.find(descriptorSet);
                    if (it == bindedSets.end())
                        return nullptr;
                        
                    return it->second;
                }
                
                void addDescriptorBinding(VkDescriptorSet descriptorSet, DescriptorPool *pool) {
                    bindedSets[descriptorSet] = pool;
                }
                
                void removeDescriptorBinding(VkDescriptorSet descriptorSet) {
                    bindedSets.erase(descriptorSet);
                }
            
                void removeCurrent(VkDevice device) {
                    auto descriptorPool = pools.back();
                    pools.pop_back();
                
                    vkDestroyDescriptorPool(device, descriptorPool->handle, nullptr);
                }
        };
        
        VkInstance instance;
        VkPhysicalDevice physicalDevice;
        VkDevice device;
        VkQueue queue;
        VkFence fence;
        VkCommandPool commandPool;
        VkCommandBuffer commandBuffer;
        VkDescriptorSetLayout descriptorSetLayout;
        VkPipelineLayout pipelineLayout;
        DescriptorPoolBuffer poolsBuffer;
        VkPipeline colorSwapPipeline;
        VulkanTable dispatchTable;
        
        bool colorSwapEnabled = false;
        bool initialized = false;
        
        std::atomic_bool isOperationPending = false;
        
        VkResult createInstance();
        VkResult pickPhysicalDevice();
        VkResult createDevice();
        VkResult createPipelines();
        void swapColors(Drawable *drawable);
        
  public:      
        VkResult createComposerTexture(Drawable *drawable);
        void destroyComposerTexture(Drawable *drawable);
        void init();
        void apply(Drawable *drawable);
        void setColorSwapEnabled(bool enabled);
        bool isColorSwapEnabled();
        bool isEnabled();
        bool isSuitableForColorSwap(Drawable *drawable);
        bool isPending();
};