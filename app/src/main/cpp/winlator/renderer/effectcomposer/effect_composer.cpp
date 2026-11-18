#include "effect_composer.hpp"
#include "color_swap_spv.h"

#define LOG_TAG "EffectComposer"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static bool enable_validation = false;

static const std::vector<const char *> layerNames = {
    "VK_LAYER_KHRONOS_validation"
};

static const std::vector<const char *> deviceExtensions = {
    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
    VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME
};

static bool areLayersPresent() {
    std::vector<VkLayerProperties> layerProps;
    uint32_t layerCount;
    VkResult result;
    
    result = vkEnumerateInstanceLayerProperties(&layerCount, nullptr);
    if (result != VK_SUCCESS) 
        return false;
    
    layerProps.resize(layerCount);
    result = vkEnumerateInstanceLayerProperties(&layerCount, layerProps.data());
    if (result != VK_SUCCESS) 
        return false;
    
    for (const auto& name : layerNames) {
        bool layerFound = false;
        for (const auto& layerProp : layerProps) {
            if (!strcmp(layerProp.layerName, name)) {
                layerFound = true;
                break;
            }
        }
        
        if (!layerFound) {
            printf("Layer %s not found", name);
            return false;
        }
    }
    
    return true;
}

void EffectComposer::setColorSwapEnabled(bool enabled) {
    colorSwapEnabled = enabled;
}

bool EffectComposer::isEnabled() {
    return initialized;
}

bool EffectComposer::isColorSwapEnabled() {
    return isEnabled() && colorSwapEnabled;
}

bool EffectComposer::isSuitableForColorSwap(Drawable *drawable) {
    return isColorSwapEnabled() && !drawable->isDirectContent && !drawable->isDisplayX;
}

bool EffectComposer::isPending() {
    return this->isOperationPending.load();
}

VkResult EffectComposer::createInstance() {
    VkResult result;
    
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "EffectComposer";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "EffectComposer";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;
    
    if (enable_validation) {
        if (!areLayersPresent()) return VK_ERROR_INITIALIZATION_FAILED;
    }

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = 0;
    createInfo.ppEnabledExtensionNames = nullptr;
    createInfo.enabledLayerCount = enable_validation ? static_cast<uint32_t>(layerNames.size()) : 0;
    createInfo.ppEnabledLayerNames = enable_validation ? layerNames.data() : nullptr;

    result = vkCreateInstance(&createInfo, nullptr, &instance);
    if (result != VK_SUCCESS) {
        printf("Failed to create instance, result %d", result);
        return result;
    }
    
    return VK_SUCCESS;
}

VkResult EffectComposer::pickPhysicalDevice() {
    std::vector<VkPhysicalDevice> physicalDevices;
    uint32_t deviceCount;
    VkResult result;

    result = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (result != VK_SUCCESS) {
        printf("Failed to enumarate physical devices, result %d", result);
        return result;
    }
    
    physicalDevices.resize(deviceCount);
    vkEnumeratePhysicalDevices(instance, &deviceCount, physicalDevices.data());
    
    physicalDevice = physicalDevices[0];
    return VK_SUCCESS;
}

static uint32_t findQueueIndex(VkPhysicalDevice physicalDevice) {
    uint32_t queueFamilyCount;
    std::vector<VkQueueFamilyProperties> queueFamilyProps;
    VkResult result;

    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
    if (queueFamilyCount == 0) {
        printf("Failed to query queue family properties, result %d", result);
        return UINT32_MAX;
    }
    
    queueFamilyProps.resize(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, queueFamilyProps.data());
    
    uint32_t index = 0;
    for (const auto& prop : queueFamilyProps) {
        if (prop.queueFlags & VK_QUEUE_COMPUTE_BIT) {
            return index;
        }
        
        index++;
    }
    
    return UINT32_MAX;
}

VkResult EffectComposer::createDevice() {
    uint32_t queueIndex;
    VkPhysicalDeviceFeatures enabledFeatures{};
    VkResult result;
    
    queueIndex = findQueueIndex(physicalDevice);
    if (queueIndex == UINT32_MAX) {
        printf("Failed to find suitable queue index");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    
    VkDeviceQueueCreateInfo queueCreateInfo{};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = queueIndex;
    queueCreateInfo.queueCount = 1;
    
    float queuePriority = 1.0f;
    queueCreateInfo.pQueuePriorities = &queuePriority;
    
    VkDeviceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.queueCreateInfoCount = 1;
    createInfo.pQueueCreateInfos = &queueCreateInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
    createInfo.ppEnabledExtensionNames = deviceExtensions.data();
    createInfo.enabledLayerCount = 0;
    createInfo.pEnabledFeatures = &enabledFeatures;
    
    result = vkCreateDevice(physicalDevice, &createInfo, nullptr, &device);
    if (result != VK_SUCCESS) {
        printf("Failed to create device, result %d", result);
        return result;
    }
    
    dispatchTable.GetFenceFdKHR = (PFN_vkGetFenceFdKHR)vkGetDeviceProcAddr(device, "vkGetFenceFdKHR");
     
    vkGetDeviceQueue(device, queueIndex, 0, &queue);

    VkFenceCreateInfo fenceCreateInfo{};
    fenceCreateInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceCreateInfo.pNext = nullptr;
    fenceCreateInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

    result = vkCreateFence(device, &fenceCreateInfo, nullptr, &fence);
    if (result != VK_SUCCESS) {
        printf("Failed to create synchronization fence, result %d", result);
        return result;
    }
    
    VkCommandPoolCreateInfo poolCreateInfo{};
    poolCreateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolCreateInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolCreateInfo.queueFamilyIndex = queueIndex;

    result = vkCreateCommandPool(device, &poolCreateInfo, nullptr, &commandPool);
    if (result != VK_SUCCESS) {
        printf("Failed to create command pool, result %d", result);
        return result;
    }
    
    VkCommandBufferAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocateInfo.commandPool = commandPool;
    allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocateInfo.commandBufferCount = 1;

    result = vkAllocateCommandBuffers(device, &allocateInfo, &commandBuffer);
    if (result != VK_SUCCESS) {
        printf("Failed to allocate command buffer, result %d", result);
        return result;
    }
    
    return VK_SUCCESS;
}

VkResult EffectComposer::createPipelines() {
    VkShaderModule colorSwapModule;
    VkResult result;
    
    VkShaderModuleCreateInfo colorSwapShaderInfo = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .codeSize = color_swap_spv_len,
        .pCode = (const uint32_t *)color_swap_spv
    };
    
    result = vkCreateShaderModule(device, &colorSwapShaderInfo, nullptr, &colorSwapModule);
    if (result != VK_SUCCESS) {
        printf("Failed to create shader module, result %d", result);
        return result;
    }
    
    VkPipelineShaderStageCreateInfo shaderStageInfos[] = {
        {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = colorSwapModule,
            .pName = "main",
            .pSpecializationInfo = nullptr
        }
    }; 
    
    VkDescriptorSetLayoutBinding bindings[] = {
        {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            .pImmutableSamplers = nullptr
        },
        {
            .binding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            .pImmutableSamplers = nullptr
        }
    };  
    
    VkDescriptorSetLayoutCreateInfo descriptorSetCreateInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .bindingCount = 2,
        .pBindings = bindings
    }; 
    
    result = vkCreateDescriptorSetLayout(device, &descriptorSetCreateInfo, nullptr, &descriptorSetLayout);
    if (result != VK_SUCCESS) {
        printf("Failed to create descriptor set layout, result %d", result);
        return result;
    }
    
    VkPushConstantRange pushConstants = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = sizeof(struct PushConstants)
    };

    VkPipelineLayoutCreateInfo layoutCreateInfo = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .setLayoutCount = 1,
        .pSetLayouts = &descriptorSetLayout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &pushConstants
    };

    result = vkCreatePipelineLayout(device, &layoutCreateInfo, nullptr, &pipelineLayout);
    if (result != VK_SUCCESS) {
        printf("Failed to create pipeline layout, result %d", result);
        return result;
    }
    
    VkComputePipelineCreateInfo pipelineCreateInfos[] = {
        {
            .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = shaderStageInfos[0],
            .layout = pipelineLayout,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
        }
    };
    
    VkPipeline pipelines[1];

    result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, pipelineCreateInfos, nullptr, pipelines);
    if (result != VK_SUCCESS) {
        printf("Failed to create compute pipelines, result %d", result);
        return result;
    }

    colorSwapPipeline = pipelines[0];

    vkDestroyShaderModule(device, colorSwapModule, nullptr);
    return VK_SUCCESS;
}

static uint32_t pick_memory_index(VkPhysicalDevice physicalDevice, uint32_t memoryBits) {
    VkPhysicalDeviceMemoryProperties memoryProps{};
    uint32_t idx;

    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProps);

    for (idx = 0; idx < memoryProps.memoryTypeCount; idx++) {
        if (memoryBits & (1u << idx))
            return idx;
    }

    return UINT32_MAX;
}

VkResult EffectComposer::createComposerTexture(Drawable *drawable) {
    VkResult result;
    int res;

    drawable->composerTexture = std::make_unique<ComposerTexture>();

    AHardwareBuffer_Desc outDesc{};
    AHardwareBuffer_describe(drawable->ahb, &outDesc);

    res = AHardwareBuffer_allocate(&outDesc, &drawable->composerTexture->dstBuffer);
    if (res != 0) {
        printf("Failed to allocate destination AHardwareBuffer, result %d\n", result);
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID srcFormatProps{};
    srcFormatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;

    VkAndroidHardwareBufferPropertiesANDROID srcAHBProps{};
    srcAHBProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    srcAHBProps.pNext = &srcFormatProps;

    result = vkGetAndroidHardwareBufferPropertiesANDROID(device, drawable->ahb, &srcAHBProps);
    if (result != VK_SUCCESS) {
        printf("Failed to query source AHardwareBuffer properties, result %d", result);
        return result;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID dstFormatProps{};
    dstFormatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;

    VkAndroidHardwareBufferPropertiesANDROID dstAHBProps{};
    dstAHBProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    dstAHBProps.pNext = &dstFormatProps;

    result = vkGetAndroidHardwareBufferPropertiesANDROID(device, drawable->composerTexture->dstBuffer, &dstAHBProps);
    if (result != VK_SUCCESS) {
        printf("Failed to query destination AHardwareBuffer properties, result %d\n", result);
        return result;
    }

    VkExternalMemoryImageCreateInfo srcExternalCreateInfo{};
    srcExternalCreateInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    srcExternalCreateInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo srcImageCreateInfo{};
    srcImageCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    srcImageCreateInfo.pNext = &srcExternalCreateInfo;
    srcImageCreateInfo.imageType = VK_IMAGE_TYPE_2D;
    srcImageCreateInfo.format = srcFormatProps.format;
    srcImageCreateInfo.extent = { static_cast<uint32_t>(drawable->width), static_cast<uint32_t>(drawable->height), 1 };
    srcImageCreateInfo.mipLevels = 1;
    srcImageCreateInfo.arrayLayers = 1;
    srcImageCreateInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    srcImageCreateInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    srcImageCreateInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    srcImageCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    srcImageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    result = vkCreateImage(device, &srcImageCreateInfo, nullptr, &drawable->composerTexture->srcImage);
    if (result != VK_SUCCESS) {
        printf("Failed to create src texture image, result %d\n", result);
        return result;
    }

    VkExternalMemoryImageCreateInfo dstExternalCreateInfo{};
    dstExternalCreateInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    dstExternalCreateInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo dstImageCreateInfo{};
    dstImageCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    dstImageCreateInfo.pNext = &dstExternalCreateInfo;
    dstImageCreateInfo.imageType = VK_IMAGE_TYPE_2D;
    dstImageCreateInfo.format = dstFormatProps.format;
    dstImageCreateInfo.extent = { static_cast<uint32_t>(drawable->width), static_cast<uint32_t>(drawable->height), 1 };
    dstImageCreateInfo.mipLevels = 1;
    dstImageCreateInfo.arrayLayers = 1;
    dstImageCreateInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    dstImageCreateInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    dstImageCreateInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT;
    dstImageCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    dstImageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    result = vkCreateImage(device, &dstImageCreateInfo, nullptr, &drawable->composerTexture->dstImage);
    if (result != VK_SUCCESS) {
        printf("Failed to create dst texture image, result %d\n", result);
        return result;
    }

    VkImportAndroidHardwareBufferInfoANDROID srcImportAHB{};
    srcImportAHB.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    srcImportAHB.buffer = drawable->ahb;
    
    VkMemoryDedicatedAllocateInfo srcDedicatedInfo{};
    srcDedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    srcDedicatedInfo.pNext = &srcImportAHB;
    srcDedicatedInfo.image = drawable->composerTexture->srcImage;
    srcDedicatedInfo.buffer = nullptr;

    VkMemoryAllocateInfo srcAllocateInfo{};
    srcAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    srcAllocateInfo.pNext = &srcDedicatedInfo;
    srcAllocateInfo.allocationSize = srcAHBProps.allocationSize;
    srcAllocateInfo.memoryTypeIndex = pick_memory_index(physicalDevice, srcAHBProps.memoryTypeBits);

    result = vkAllocateMemory(device, &srcAllocateInfo, nullptr, &drawable->composerTexture->srcMemory);
    if (result != VK_SUCCESS) {
        printf("Failed to allocate src texture memory, result %d\n", result);
        return result;
    }

    result = vkBindImageMemory(device, drawable->composerTexture->srcImage, drawable->composerTexture->srcMemory, 0);
    if (result != VK_SUCCESS) {
        printf("Failed to bind src texture image memory, result %d\n", result);
        return result;
    }

    VkImportAndroidHardwareBufferInfoANDROID dstImportAHB{};
    dstImportAHB.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    dstImportAHB.buffer = drawable->composerTexture->dstBuffer;
    
    VkMemoryDedicatedAllocateInfo dstDedicatedInfo{};
    dstDedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dstDedicatedInfo.pNext = &dstImportAHB;
    dstDedicatedInfo.image = drawable->composerTexture->dstImage;
    dstDedicatedInfo.buffer = nullptr;

    VkMemoryAllocateInfo dstAllocateInfo{};
    dstAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    dstAllocateInfo.pNext = &dstDedicatedInfo;
    dstAllocateInfo.allocationSize = dstAHBProps.allocationSize;
    dstAllocateInfo.memoryTypeIndex = pick_memory_index(physicalDevice, dstAHBProps.memoryTypeBits);

    result = vkAllocateMemory(device, &dstAllocateInfo, nullptr, &drawable->composerTexture->dstMemory);
    if (result != VK_SUCCESS) {
        printf("Failed to allocate dst texture memory, result %d\n", result);
        return result;
    }

    result = vkBindImageMemory(device, drawable->composerTexture->dstImage, drawable->composerTexture->dstMemory, 0);
    if (result != VK_SUCCESS) {
        printf("Failed to bind dst texture image memory, result %d\n", result);
        return result;
    }

    VkComponentMapping componentsMapping{
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY
    };

    VkImageSubresourceRange subresourceRange{
        VK_IMAGE_ASPECT_COLOR_BIT,
        0,
        1,
        0,
        1
    };

    VkImageViewCreateInfo srcImageViewCreateInfo{};
    srcImageViewCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    srcImageViewCreateInfo.image = drawable->composerTexture->srcImage;
    srcImageViewCreateInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    srcImageViewCreateInfo.format = srcFormatProps.format;
    srcImageViewCreateInfo.components = componentsMapping;
    srcImageViewCreateInfo.subresourceRange = subresourceRange;

    result = vkCreateImageView(device, &srcImageViewCreateInfo, nullptr, &drawable->composerTexture->srcImageView);
    if (result != VK_SUCCESS) {
        printf("Failed to create src texture image view, result %d\n", result);
        return result;
    }

    VkImageViewCreateInfo dstImageViewCreateInfo{};
    dstImageViewCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    dstImageViewCreateInfo.image = drawable->composerTexture->dstImage;
    dstImageViewCreateInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    dstImageViewCreateInfo.format = dstFormatProps.format;
    dstImageViewCreateInfo.components = componentsMapping;
    dstImageViewCreateInfo.subresourceRange = subresourceRange;

    result = vkCreateImageView(device, &dstImageViewCreateInfo, nullptr, &drawable->composerTexture->dstImageView);
    if (result != VK_SUCCESS) {
        printf("Failed to create dst texture image view, result %d\n", result);
        return result;
    }

    VkSamplerCreateInfo srcSamplerCreateInfo{};
    srcSamplerCreateInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    srcSamplerCreateInfo.pNext = nullptr;
    srcSamplerCreateInfo.flags = 0;
    srcSamplerCreateInfo.magFilter = VK_FILTER_NEAREST;
    srcSamplerCreateInfo.minFilter = VK_FILTER_NEAREST;
    srcSamplerCreateInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    srcSamplerCreateInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    srcSamplerCreateInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    srcSamplerCreateInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    srcSamplerCreateInfo.mipLodBias = 0.0f;
    srcSamplerCreateInfo.anisotropyEnable = VK_FALSE;
    srcSamplerCreateInfo.compareEnable = VK_FALSE;
    srcSamplerCreateInfo.minLod = 0.0f;
    srcSamplerCreateInfo.maxLod = 0.0f;
    srcSamplerCreateInfo.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    srcSamplerCreateInfo.unnormalizedCoordinates = VK_TRUE;
    
    result = vkCreateSampler(device, &srcSamplerCreateInfo, nullptr, &drawable->composerTexture->srcSampler);
    if (result != VK_SUCCESS) {
        printf("Failed to create srcImage sampler, result %d\n", result);
        return result;
    }
    
    auto descriptorPool = poolsBuffer.findFreePool(device);

    VkDescriptorSetAllocateInfo descriptorSetAllocateInfo{};
    descriptorSetAllocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    descriptorSetAllocateInfo.descriptorPool = descriptorPool->handle;
    descriptorSetAllocateInfo.descriptorSetCount = 1;
    descriptorSetAllocateInfo.pSetLayouts = &descriptorSetLayout;

    result = vkAllocateDescriptorSets(device, &descriptorSetAllocateInfo, &drawable->composerTexture->vkDescriptorSet);
    if (result != VK_SUCCESS) {
        printf("Failed to allocate descriptor sets, result %d\n", result);
        return result;
    }
    
    VkDescriptorImageInfo srcImageInfo{};
    srcImageInfo.sampler = drawable->composerTexture->srcSampler;
    srcImageInfo.imageView = drawable->composerTexture->srcImageView;
    srcImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkDescriptorImageInfo dstImageInfo{};
    dstImageInfo.imageView = drawable->composerTexture->dstImageView;
    dstImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet descWrites[2]{};

    descWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descWrites[0].dstSet = drawable->composerTexture->vkDescriptorSet;
    descWrites[0].dstBinding = 0;
    descWrites[0].descriptorCount = 1;
    descWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    descWrites[0].pImageInfo = &srcImageInfo;

    descWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descWrites[1].dstSet = drawable->composerTexture->vkDescriptorSet;
    descWrites[1].dstBinding = 1;
    descWrites[1].descriptorCount = 1;
    descWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    descWrites[1].pImageInfo = &dstImageInfo;

    vkUpdateDescriptorSets(device, 2, descWrites, 0, nullptr);

    descriptorPool->addBindedImage(drawable->composerTexture->srcImage);
    descriptorPool->addBindedImage(drawable->composerTexture->dstImage);
    poolsBuffer.addDescriptorBinding(drawable->composerTexture->vkDescriptorSet, descriptorPool);

    drawable->composerTexture->srcImageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    drawable->composerTexture->srcPipelineStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    drawable->composerTexture->srcAccessFlags = static_cast<VkAccessFlagBits>(0);
    
    drawable->composerTexture->dstImageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    drawable->composerTexture->dstPipelineStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    drawable->composerTexture->dstAccessFlags = static_cast<VkAccessFlagBits>(0);

    return VK_SUCCESS;
}

void EffectComposer::destroyComposerTexture(Drawable *drawable) {
    vkDeviceWaitIdle(device);
    
    if (!drawable->composerTexture) 
        return;
    
    auto descriptorPool = poolsBuffer.getPoolForSet(drawable->composerTexture->vkDescriptorSet);
    if (descriptorPool) {
        descriptorPool->removeBindedImage(drawable->composerTexture->srcImage);
        descriptorPool->removeBindedImage(drawable->composerTexture->dstImage);
        poolsBuffer.removeDescriptorBinding(drawable->composerTexture->vkDescriptorSet);
        vkFreeDescriptorSets(device, descriptorPool->handle, 1, &drawable->composerTexture->vkDescriptorSet);
    }
    
    if (drawable->composerTexture->srcSampler != VK_NULL_HANDLE)
        vkDestroySampler(device, drawable->composerTexture->srcSampler, nullptr);
    if (drawable->composerTexture->srcMemory != VK_NULL_HANDLE) 
        vkFreeMemory(device, drawable->composerTexture->srcMemory, nullptr);
    if (drawable->composerTexture->srcImageView != VK_NULL_HANDLE)
        vkDestroyImageView(device, drawable->composerTexture->srcImageView, nullptr);
    if (drawable->composerTexture->srcImage != VK_NULL_HANDLE)    
        vkDestroyImage(device, drawable->composerTexture->srcImage, nullptr);
    
    if (drawable->composerTexture->dstMemory != VK_NULL_HANDLE) 
        vkFreeMemory(device, drawable->composerTexture->dstMemory, nullptr);
    if (drawable->composerTexture->dstImageView != VK_NULL_HANDLE)    
        vkDestroyImageView(device, drawable->composerTexture->dstImageView, nullptr);
    if (drawable->composerTexture->dstImage != VK_NULL_HANDLE)     
        vkDestroyImage(device, drawable->composerTexture->dstImage, nullptr);
    
    if (drawable->composerTexture->dstBuffer)
        AHardwareBuffer_release(drawable->composerTexture->dstBuffer);
        
    if (drawable->composerTexture)
        drawable->composerTexture.reset();
}    

void EffectComposer::swapColors(Drawable *drawable) {
    VkImageSubresourceRange subresourceRange = {
        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
        .baseMipLevel = 0,
        .levelCount = 1,
        .baseArrayLayer = 0,
        .layerCount = 1
    };
    
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, colorSwapPipeline);
      
    if (drawable->composerTexture->srcPipelineStage != VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT ||
        !(drawable->composerTexture->srcAccessFlags & (VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT)) ||
        drawable->composerTexture->srcImageLayout != VK_IMAGE_LAYOUT_GENERAL) {
            
        VkImageMemoryBarrier initialImageBarrier{};
        initialImageBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        initialImageBarrier.pNext = nullptr;
        initialImageBarrier.srcAccessMask = drawable->composerTexture->srcAccessFlags;
        initialImageBarrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
        initialImageBarrier.oldLayout =  drawable->composerTexture->srcImageLayout;
        initialImageBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        initialImageBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        initialImageBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        initialImageBarrier.image = drawable->composerTexture->srcImage;
        initialImageBarrier.subresourceRange = subresourceRange;
        
        vkCmdPipelineBarrier(commandBuffer, drawable->composerTexture->srcPipelineStage, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &initialImageBarrier);
            
        drawable->composerTexture->srcImageLayout = VK_IMAGE_LAYOUT_GENERAL;
        drawable->composerTexture->srcPipelineStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        drawable->composerTexture->srcAccessFlags = static_cast<VkAccessFlagBits>(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
    }
    
          
    if (drawable->composerTexture->dstPipelineStage != VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT ||
        !(drawable->composerTexture->dstAccessFlags & (VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT)) ||
        drawable->composerTexture->dstImageLayout != VK_IMAGE_LAYOUT_GENERAL) {
            
        VkImageMemoryBarrier initialImageBarrier{};
        initialImageBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        initialImageBarrier.pNext = nullptr;
        initialImageBarrier.srcAccessMask = drawable->composerTexture->dstAccessFlags;
        initialImageBarrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
        initialImageBarrier.oldLayout = drawable->composerTexture->dstImageLayout;
        initialImageBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        initialImageBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        initialImageBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        initialImageBarrier.image = drawable->composerTexture->dstImage;
        initialImageBarrier.subresourceRange = subresourceRange;
        
        vkCmdPipelineBarrier(commandBuffer, drawable->composerTexture->dstPipelineStage, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &initialImageBarrier);
            
        drawable->composerTexture->dstImageLayout = VK_IMAGE_LAYOUT_GENERAL;
        drawable->composerTexture->dstPipelineStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        drawable->composerTexture->dstAccessFlags = static_cast<VkAccessFlagBits>(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
    }
    
    PushConstants constants{};
    constants.width = drawable->width;
    constants.height = drawable->height;
    
    vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), &constants);
    vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &drawable->composerTexture->vkDescriptorSet, 0, nullptr);
    vkCmdDispatch(commandBuffer, (drawable->width + 15) / 16, (drawable->height + 15) / 16, 1);
}

void EffectComposer::apply(Drawable *drawable) {
    isOperationPending = true;
    
    if (!drawable->composerTexture) {
        VkResult result = createComposerTexture(drawable);
        if (result != VK_SUCCESS) {
            printf("Failed to create composer texture, result %d", result);
            destroyComposerTexture(drawable);
            isOperationPending = false;
            return;
        }
        drawable->composerTexture->sizeChanged = false;
    }
    else if (drawable->composerTexture->sizeChanged) {
        destroyComposerTexture(drawable);
        VkResult result = createComposerTexture(drawable);
        if (result != VK_SUCCESS) {
            printf("Failed to resize composer texture, result %d", result);
            destroyComposerTexture(drawable);
            isOperationPending = false;
            return;
        }
        drawable->composerTexture->sizeChanged = false;
    }
    
    vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
    vkResetFences(device, 1, &fence);
    
    vkResetCommandBuffer(commandBuffer, 0);
    
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.pNext = nullptr;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    beginInfo.pInheritanceInfo = nullptr;
    
    vkBeginCommandBuffer(commandBuffer, &beginInfo);
    
    if (isSuitableForColorSwap(drawable)) {
        swapColors(drawable);
    }    
    
    vkEndCommandBuffer(commandBuffer);
    
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.pNext = nullptr;
    submitInfo.waitSemaphoreCount = 0;
    submitInfo.pWaitSemaphores = nullptr;
    submitInfo.pWaitDstStageMask = nullptr;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    submitInfo.signalSemaphoreCount = 0;
    submitInfo.pSignalSemaphores = nullptr;
    
    vkQueueSubmit(queue, 1, &submitInfo, fence);
    
    isOperationPending = false;
}

void EffectComposer::init() {
    VkResult result;
    
    result = createInstance();
    if (result != VK_SUCCESS) {
        printf("Failed to create instance, result %d", result);
        return;
    }
    
    result = pickPhysicalDevice();
    if (result != VK_SUCCESS) {
        printf("Failed to find a suitable physical device, result %d", result);
        return;
    }
    
    result = createDevice();
    if (result != VK_SUCCESS) {
        printf("Failed to create device, result %d", result);
        return;
    }
    
    result = createPipelines();
    if (result != VK_SUCCESS) {
        printf("Failed to create pipelines, result %d", result);
        return;
    }
    
    initialized = true;
}
