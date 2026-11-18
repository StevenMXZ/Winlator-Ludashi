#pragma once

#include <mutex>

#include "renderer_jni.hpp"

struct GLTexture {
    int id;
    bool isDirty;
    EGLImageKHR eglImage;
    bool sizeChanged;
};

struct ComposerTexture {
    bool sizeChanged;
    AHardwareBuffer *srcBuffer;
    VkImage srcImage;
    VkImageView srcImageView;
    VkDeviceMemory srcMemory;
    VkSampler srcSampler;
    AHardwareBuffer *dstBuffer;
    VkImage dstImage;
    VkImageView dstImageView;
    VkDeviceMemory dstMemory;
    VkImageLayout srcImageLayout;
    VkPipelineStageFlagBits srcPipelineStage;
    VkAccessFlagBits srcAccessFlags;
    VkImageLayout dstImageLayout;
    VkPipelineStageFlagBits dstPipelineStage;
    VkAccessFlagBits dstAccessFlags;
    VkDescriptorSet vkDescriptorSet;
};

struct Drawable { 
    int id;
    int width;
    int format;
    int height;
    int stride;
    std::unique_ptr<GLTexture> glTexture;
    std::unique_ptr<ComposerTexture> composerTexture;
    bool isDirectContent;
    bool isDisplayX;
    void *data;
    AHardwareBuffer *ahb;
    int sync_fence;
};