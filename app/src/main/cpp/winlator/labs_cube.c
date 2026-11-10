// Vulkan 3D cube benchmark for Winlator Labs.
// Included from vulkan.c so it reuses the selected AdrenoTools loader.
// This is never called from the gameplay renderer.
#include "labs_cube_vert.h"
#include "labs_cube_frag.h"
#include <math.h>

#define CUBE_SIDE 640
#define CUBE_BYTES ((VkDeviceSize)CUBE_SIDE * CUBE_SIDE * 4)
#define CUBE_WARMUP_FRAMES 32
#define CUBE_MEASURED_FRAMES 140
#define CUBE_PROFILE_COUNT 3
#define CUBE_BENCHMARK_VERSION 3

// Same, bounded workloads for every driver. Each has a distinct bottleneck.
// Geometry: 8 * 48; shading: 3 * 12; balanced: 5 * 24 cubes per frame.
static const uint32_t cube_draws[CUBE_PROFILE_COUNT] = {8, 3, 5};
static const uint32_t cube_instances[CUBE_PROFILE_COUNT] = {48, 12, 24};
static const uint32_t cube_columns[CUBE_PROFILE_COUNT] = {8, 4, 6};
static const uint32_t cube_shader_steps[CUBE_PROFILE_COUNT] = {0, 8, 4};

static uint32_t cube_mem_type(uint32_t supported,
        const VkPhysicalDeviceMemoryProperties *memory, VkMemoryPropertyFlags required) {
    for (uint32_t i = 0; i < memory->memoryTypeCount; i++)
        if ((supported & (1u << i)) &&
            (memory->memoryTypes[i].propertyFlags & required) == required) return i;
    return UINT32_MAX;
}

static double cube_now(void) {
    struct timespec t = {0};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1.0e9;
}
static int cube_sort_double(const void *a, const void *b) {
    double left = *(const double *)a, right = *(const double *)b;
    return (left > right) - (left < right);
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_GPUInformation_runGpuCubeBenchmark(
        JNIEnv *env, jclass unused, jstring driverName, jobject context, jobject reporter) {
    char output[320] = "ERROR\tUnable to initialize 3D test";
    const char *error = "Initializing Vulkan";
    VkInstance lab_instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkImage images[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkDeviceMemory image_mem[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkImageView views[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkBuffer read_buffer = VK_NULL_HANDLE;
    VkDeviceMemory read_memory = VK_NULL_HANDLE;
    VkRenderPass pass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkQueryPool query_pool = VK_NULL_HANDLE;
    VkShaderModule shaders[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkPhysicalDeviceMemoryProperties memory = {0};
    VkPhysicalDeviceProperties properties = {0};
    VkQueueFamilyProperties *families = NULL;
    uint32_t family = UINT32_MAX, family_count = 0;
    uint32_t timestamp_bits = 0;
    double gpu_samples[CUBE_MEASURED_FRAMES] = {0};
    double gpu_median[CUBE_PROFILE_COUNT] = {0};
    double gpu_p95[CUBE_PROFILE_COUNT] = {0};
    uint32_t accepted = 0;
    double benchmark_deadline = 0;
    bool ready = false;
    bool read_coherent = false;
    bool mapped_read = false;
    void *pixels = NULL;
    PFN_vkGetDeviceProcAddr gdpa = NULL;
    PFN_vkGetPhysicalDeviceMemoryProperties pMemory = NULL;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties pFamilies = NULL;
    PFN_vkGetPhysicalDeviceFormatProperties pFormat = NULL;
    PFN_vkCreateDevice pCreateDevice = NULL;
    jmethodID onFrame = NULL;

    PFN_vkDestroyDevice D = NULL;
    PFN_vkGetDeviceQueue getQueue = NULL;
    PFN_vkCreateCommandPool createPool = NULL;
    PFN_vkDestroyCommandPool destroyPool = NULL;
    PFN_vkAllocateCommandBuffers allocCmd = NULL;
    PFN_vkResetCommandPool resetPool = NULL;
    PFN_vkBeginCommandBuffer beginCmd = NULL;
    PFN_vkEndCommandBuffer endCmd = NULL;
    PFN_vkQueueSubmit submit = NULL;
    PFN_vkQueueWaitIdle waitIdle = NULL;
    PFN_vkCreateImage createImage = NULL;
    PFN_vkDestroyImage destroyImage = NULL;
    PFN_vkGetImageMemoryRequirements imageReq = NULL;
    PFN_vkCreateImageView createView = NULL;
    PFN_vkDestroyImageView destroyView = NULL;
    PFN_vkCreateBuffer createBuffer = NULL;
    PFN_vkDestroyBuffer destroyBuffer = NULL;
    PFN_vkGetBufferMemoryRequirements bufferReq = NULL;
    PFN_vkAllocateMemory allocMemory = NULL;
    PFN_vkFreeMemory freeMemory = NULL;
    PFN_vkBindImageMemory bindImage = NULL;
    PFN_vkBindBufferMemory bindBuffer = NULL;
    PFN_vkMapMemory mapMemory = NULL;
    PFN_vkUnmapMemory unmapMemory = NULL;
    PFN_vkInvalidateMappedMemoryRanges invalidate = NULL;
    PFN_vkCreateRenderPass createPass = NULL;
    PFN_vkDestroyRenderPass destroyPass = NULL;
    PFN_vkCreateFramebuffer createFramebuffer = NULL;
    PFN_vkDestroyFramebuffer destroyFramebuffer = NULL;
    PFN_vkCreateShaderModule createShader = NULL;
    PFN_vkDestroyShaderModule destroyShader = NULL;
    PFN_vkCreatePipelineLayout createLayout = NULL;
    PFN_vkDestroyPipelineLayout destroyLayout = NULL;
    PFN_vkCreateGraphicsPipelines createPipelines = NULL;
    PFN_vkDestroyPipeline destroyPipeline = NULL;
    PFN_vkCmdBeginRenderPass cmdBeginPass = NULL;
    PFN_vkCmdEndRenderPass cmdEndPass = NULL;
    PFN_vkCmdBindPipeline cmdBindPipeline = NULL;
    PFN_vkCmdPushConstants pushConstants = NULL;
    PFN_vkCmdDraw draw = NULL;
    PFN_vkCmdCopyImageToBuffer copyImage = NULL;
    PFN_vkCmdPipelineBarrier barrier = NULL;
    PFN_vkCreateQueryPool createQueryPool = NULL;
    PFN_vkDestroyQueryPool destroyQueryPool = NULL;
    PFN_vkCmdResetQueryPool resetQuery = NULL;
    PFN_vkCmdWriteTimestamp writeTimestamp = NULL;
    PFN_vkGetQueryPoolResults queryResults = NULL;

    error = "Unable to create Vulkan instance";
    if (create_instance_impl(driverName, env, context, 1) != VK_SUCCESS) goto finish;
    lab_instance = instance;
    error = "Unable to enumerate Vulkan device";
    if (enumerate_physical_devices() != VK_SUCCESS) goto finish;
    PFN_vkGetInstanceProcAddr gip =
        (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    if (!gip) goto finish;
#define CUBE_LOAD_INSTANCE(dst, vkname) \
    dst = (PFN_vk##vkname)gip(lab_instance, "vk" #vkname); \
    if (!dst) { error = "Missing Vulkan function: " #vkname; goto finish; }
    CUBE_LOAD_INSTANCE(pMemory, GetPhysicalDeviceMemoryProperties);
    CUBE_LOAD_INSTANCE(pFamilies, GetPhysicalDeviceQueueFamilyProperties);
    CUBE_LOAD_INSTANCE(pFormat, GetPhysicalDeviceFormatProperties);
    CUBE_LOAD_INSTANCE(pCreateDevice, CreateDevice);
#undef CUBE_LOAD_INSTANCE
    gdpa = (PFN_vkGetDeviceProcAddr)gip(lab_instance, "vkGetDeviceProcAddr");
    if (!gdpa) { error = "Vulkan device dispatch unavailable"; goto finish; }
    getPhysicalDeviceProperties(physicalDevice, &properties);
    VkFormatProperties color_support = {0}, depth_support = {0};
    pFormat(physicalDevice, VK_FORMAT_R8G8B8A8_UNORM, &color_support);
    pFormat(physicalDevice, VK_FORMAT_D16_UNORM, &depth_support);
    if (!(color_support.optimalTilingFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) ||
        !(color_support.optimalTilingFeatures & VK_FORMAT_FEATURE_TRANSFER_SRC_BIT)) {
        error = "RGBA8 color render/transfer unsupported"; goto finish;
    }
    if (!(depth_support.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)) {
        error = "D16 depth buffer unsupported"; goto finish;
    }
    pMemory(physicalDevice, &memory);
    pFamilies(physicalDevice, &family_count, NULL);
    if (family_count == 0 || family_count > 64) {
        error = "No Vulkan queue families"; goto finish;
    }
    families = calloc(family_count, sizeof(*families));
    if (!families) { error = "Out of memory"; goto finish; }
    pFamilies(physicalDevice, &family_count, families);
    for (uint32_t i = 0; i < family_count; i++) {
        if (families[i].queueCount && (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
            family = i; break;
        }
    }
    if (family == UINT32_MAX) { error = "No graphics queue"; goto finish; }
    timestamp_bits = families[family].timestampValidBits;
    if (!timestamp_bits || properties.limits.timestampPeriod <= 0.0f) {
        error = "Driver does not support valid GPU timestamp queries";
        goto finish;
    }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority
    };
    VkDeviceCreateInfo deviceInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1, .pQueueCreateInfos = &queueInfo
    };
    error = "Unable to create Vulkan graphics device";
    if (pCreateDevice(physicalDevice, &deviceInfo, NULL, &device) != VK_SUCCESS) goto finish;
#define CUBE_LOAD(dst, vkname) \
    dst = (PFN_vk##vkname)gdpa(device, "vk" #vkname); \
    if (!dst) { error = "Missing Vulkan function: " #vkname; goto finish; }
    CUBE_LOAD(D, DestroyDevice);
    CUBE_LOAD(getQueue, GetDeviceQueue);
    CUBE_LOAD(createPool, CreateCommandPool);
    CUBE_LOAD(destroyPool, DestroyCommandPool);
    CUBE_LOAD(allocCmd, AllocateCommandBuffers);
    CUBE_LOAD(resetPool, ResetCommandPool);
    CUBE_LOAD(beginCmd, BeginCommandBuffer);
    CUBE_LOAD(endCmd, EndCommandBuffer);
    CUBE_LOAD(submit, QueueSubmit);
    CUBE_LOAD(waitIdle, QueueWaitIdle);
    CUBE_LOAD(createImage, CreateImage);
    CUBE_LOAD(destroyImage, DestroyImage);
    CUBE_LOAD(imageReq, GetImageMemoryRequirements);
    CUBE_LOAD(createView, CreateImageView);
    CUBE_LOAD(destroyView, DestroyImageView);
    CUBE_LOAD(createBuffer, CreateBuffer);
    CUBE_LOAD(destroyBuffer, DestroyBuffer);
    CUBE_LOAD(bufferReq, GetBufferMemoryRequirements);
    CUBE_LOAD(allocMemory, AllocateMemory);
    CUBE_LOAD(freeMemory, FreeMemory);
    CUBE_LOAD(bindImage, BindImageMemory);
    CUBE_LOAD(bindBuffer, BindBufferMemory);
    CUBE_LOAD(mapMemory, MapMemory);
    CUBE_LOAD(unmapMemory, UnmapMemory);
    CUBE_LOAD(invalidate, InvalidateMappedMemoryRanges);
    CUBE_LOAD(createPass, CreateRenderPass);
    CUBE_LOAD(destroyPass, DestroyRenderPass);
    CUBE_LOAD(createFramebuffer, CreateFramebuffer);
    CUBE_LOAD(destroyFramebuffer, DestroyFramebuffer);
    CUBE_LOAD(createShader, CreateShaderModule);
    CUBE_LOAD(destroyShader, DestroyShaderModule);
    CUBE_LOAD(createLayout, CreatePipelineLayout);
    CUBE_LOAD(destroyLayout, DestroyPipelineLayout);
    CUBE_LOAD(createPipelines, CreateGraphicsPipelines);
    CUBE_LOAD(destroyPipeline, DestroyPipeline);
    CUBE_LOAD(cmdBeginPass, CmdBeginRenderPass);
    CUBE_LOAD(cmdEndPass, CmdEndRenderPass);
    CUBE_LOAD(cmdBindPipeline, CmdBindPipeline);
    CUBE_LOAD(pushConstants, CmdPushConstants);
    CUBE_LOAD(draw, CmdDraw);
    CUBE_LOAD(copyImage, CmdCopyImageToBuffer);
    CUBE_LOAD(barrier, CmdPipelineBarrier);
    CUBE_LOAD(createQueryPool, CreateQueryPool);
    CUBE_LOAD(destroyQueryPool, DestroyQueryPool);
    CUBE_LOAD(resetQuery, CmdResetQueryPool);
    CUBE_LOAD(writeTimestamp, CmdWriteTimestamp);
    CUBE_LOAD(queryResults, GetQueryPoolResults);
#undef CUBE_LOAD
    VkQueryPoolCreateInfo queryInfo = {
        .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
        .queryType = VK_QUERY_TYPE_TIMESTAMP,
        .queryCount = 2
    };
    error = "Unable to create GPU timestamp query pool";
    if (createQueryPool(device, &queryInfo, NULL, &query_pool) != VK_SUCCESS) goto finish;
    getQueue(device, family, 0, &queue);
    if (!queue) { error = "Graphics queue unavailable"; goto finish; }

    error = "Unable to allocate cube images";
    const VkFormat formats[2] = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D16_UNORM};
    for (int i = 0; i < 2; i++) {
        VkImageCreateInfo info = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
            .imageType = VK_IMAGE_TYPE_2D,
            .format = formats[i],
            .extent = {CUBE_SIDE, CUBE_SIDE, 1},
            .mipLevels = 1, .arrayLayers = 1,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .tiling = VK_IMAGE_TILING_OPTIMAL,
            .usage = i == 0
                ? (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED
        };
        if (createImage(device, &info, NULL, &images[i]) != VK_SUCCESS) goto finish;
        VkMemoryRequirements req = {0};
        imageReq(device, images[i], &req);
        uint32_t type = cube_mem_type(req.memoryTypeBits, &memory, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type == UINT32_MAX) type = cube_mem_type(req.memoryTypeBits, &memory, 0);
        if (type == UINT32_MAX) { error = "No image memory type"; goto finish; }
        VkMemoryAllocateInfo mem = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = req.size, .memoryTypeIndex = type
        };
        if (allocMemory(device, &mem, NULL, &image_mem[i]) != VK_SUCCESS) goto finish;
        if (bindImage(device, images[i], image_mem[i], 0) != VK_SUCCESS) goto finish;
        VkImageViewCreateInfo view = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = images[i], .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = formats[i],
            .subresourceRange = {
                .aspectMask = i == 0 ? VK_IMAGE_ASPECT_COLOR_BIT : VK_IMAGE_ASPECT_DEPTH_BIT,
                .baseMipLevel = 0, .levelCount = 1, .baseArrayLayer = 0, .layerCount = 1
            }
        };
        if (createView(device, &view, NULL, &views[i]) != VK_SUCCESS) goto finish;
    }

    error = "Unable to create cube preview buffer";
    VkBufferCreateInfo bi = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = CUBE_BYTES, .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    if (createBuffer(device, &bi, NULL, &read_buffer) != VK_SUCCESS) goto finish;
    VkMemoryRequirements bufferRequirements = {0};
    bufferReq(device, read_buffer, &bufferRequirements);
    uint32_t memType = cube_mem_type(bufferRequirements.memoryTypeBits, &memory,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType != UINT32_MAX) read_coherent = true;
    else memType = cube_mem_type(bufferRequirements.memoryTypeBits, &memory,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    if (memType == UINT32_MAX) { error = "No host-visible preview memory"; goto finish; }
    VkMemoryAllocateInfo memInfo = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = bufferRequirements.size, .memoryTypeIndex = memType
    };
    if (allocMemory(device, &memInfo, NULL, &read_memory) != VK_SUCCESS) goto finish;
    if (bindBuffer(device, read_buffer, read_memory, 0) != VK_SUCCESS) goto finish;

    VkAttachmentDescription attachments[2] = {
        { .format = formats[0], .samples = VK_SAMPLE_COUNT_1_BIT,
          .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
          .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
          .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
          .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
          .finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL },
        { .format = formats[1], .samples = VK_SAMPLE_COUNT_1_BIT,
          .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR, .storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
          .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
          .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
          .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
          .finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL }
    };
    VkAttachmentReference colorReference = { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
    VkAttachmentReference depthReference = { 1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL };
    VkSubpassDescription subpass = {
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .colorAttachmentCount = 1, .pColorAttachments = &colorReference,
        .pDepthStencilAttachment = &depthReference
    };
    VkSubpassDependency dependencies[2] = {
        { .srcSubpass = VK_SUBPASS_EXTERNAL, .dstSubpass = 0,
          .srcStageMask = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
          .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                          VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
          .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                           VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT },
        { .srcSubpass = 0, .dstSubpass = VK_SUBPASS_EXTERNAL,
          .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
          .dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT,
          .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
          .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT }
    };
    VkRenderPassCreateInfo rpInfo = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .attachmentCount = 2, .pAttachments = attachments,
        .subpassCount = 1, .pSubpasses = &subpass,
        .dependencyCount = 2, .pDependencies = dependencies
    };
    error = "Unable to create cube render pass";
    if (createPass(device, &rpInfo, NULL, &pass) != VK_SUCCESS) goto finish;
    VkFramebufferCreateInfo fbInfo = {
        .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
        .renderPass = pass, .attachmentCount = 2, .pAttachments = views,
        .width = CUBE_SIDE, .height = CUBE_SIDE, .layers = 1
    };
    if (createFramebuffer(device, &fbInfo, NULL, &framebuffer) != VK_SUCCESS) goto finish;

    const uint32_t *shaderData[] = { labs_cube_vert_code, labs_cube_frag_code };
    const size_t shaderBytes[] = { sizeof(labs_cube_vert_code), sizeof(labs_cube_frag_code) };
    for (int i = 0; i < 2; i++) {
        VkShaderModuleCreateInfo info = {
            .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            .codeSize = shaderBytes[i], .pCode = shaderData[i]
        };
        error = "Cube shader creation failed";
        if (createShader(device, &info, NULL, &shaders[i]) != VK_SUCCESS) goto finish;
    }
    VkPushConstantRange push = {
        .stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
        .offset = 0, .size = 8 * sizeof(float)
    };
    VkPipelineLayoutCreateInfo layoutInfo = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pushConstantRangeCount = 1, .pPushConstantRanges = &push
    };
    error = "Cube pipeline layout failed";
    if (createLayout(device, &layoutInfo, NULL, &layout) != VK_SUCCESS) goto finish;
    VkPipelineShaderStageCreateInfo stages[2] = {
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
          .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = shaders[0], .pName = "main" },
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
          .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = shaders[1], .pName = "main" }
    };
    VkPipelineVertexInputStateCreateInfo vertexInput = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO
    };
    VkPipelineInputAssemblyStateCreateInfo inputAssembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
    };
    VkViewport viewport = {0, 0, CUBE_SIDE, CUBE_SIDE, 0, 1};
    VkRect2D scissor = { {0,0}, {CUBE_SIDE,CUBE_SIDE} };
    VkPipelineViewportStateCreateInfo viewportState = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1, .pViewports = &viewport,
        .scissorCount = 1, .pScissors = &scissor
    };
    VkPipelineRasterizationStateCreateInfo raster = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
        .lineWidth = 1.0f
    };
    VkPipelineMultisampleStateCreateInfo multisample = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT
    };
    VkPipelineDepthStencilStateCreateInfo depth = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO,
        .depthTestEnable = VK_TRUE, .depthWriteEnable = VK_TRUE,
        .depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL
    };
    VkPipelineColorBlendAttachmentState colorBlendAttachment = {
        .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                          VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT
    };
    VkPipelineColorBlendStateCreateInfo blend = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1, .pAttachments = &colorBlendAttachment
    };
    VkGraphicsPipelineCreateInfo pipelineInfo = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = 2, .pStages = stages,
        .pVertexInputState = &vertexInput, .pInputAssemblyState = &inputAssembly,
        .pViewportState = &viewportState, .pRasterizationState = &raster,
        .pMultisampleState = &multisample, .pDepthStencilState = &depth,
        .pColorBlendState = &blend, .layout = layout, .renderPass = pass,
        .subpass = 0
    };
    error = "Cube graphics pipeline creation failed";
    if (createPipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, NULL, &pipeline)
            != VK_SUCCESS) goto finish;

    VkCommandPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .queueFamilyIndex = family, .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
    };
    error = "Cube command pool failed";
    if (createPool(device, &poolInfo, NULL, &pool) != VK_SUCCESS) goto finish;
    VkCommandBufferAllocateInfo cmdInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1
    };
    if (allocCmd(device, &cmdInfo, &cmd) != VK_SUCCESS) goto finish;
    if (reporter) {
        jclass cls = (*env)->GetObjectClass(env, reporter);
        if (cls) {
            onFrame = (*env)->GetMethodID(env, cls, "onCubeFrame", "([BII)V");
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                onFrame = NULL;
            }
            (*env)->DeleteLocalRef(env, cls);
        }
    }
    // GPU Benchmark Suite v3: three independent lightweight bottleneck profiles.
    // Only GPU timestamps inside the render pass count towards scores.
    // Preview frames are separate warmup commands and never enter the samples.
    // Extend the sample window so the same-driver result is based on sustained
    // GPU measurements rather than a sub-second burst. Deliberate pacing is
    // OUTSIDE the timestamp interval and does not reduce the reported GPU FPS.
    benchmark_deadline = cube_now() + 24.0;
    error = "GPU benchmark timed out or failed";
    for (int profile = 0; profile < CUBE_PROFILE_COUNT; ++profile) {
        accepted = 0;
        const uint32_t instances = cube_instances[profile];
        const uint32_t columns = cube_columns[profile];
        const uint32_t draw_count = cube_draws[profile];
        const uint32_t shader_steps = cube_shader_steps[profile];

        for (int frame = 0; frame < CUBE_WARMUP_FRAMES + CUBE_MEASURED_FRAMES; ++frame) {
            if (cube_now() > benchmark_deadline) {
                error = "GPU benchmark exceeded 24-second safety limit";
                goto finish;
            }
            // Three real animated frames of the visually cleaner single cube.
            bool capture = onFrame != NULL && profile == 0 &&
                           frame >= 1 && frame <= 3;
            if (resetPool(device, pool, 0) != VK_SUCCESS) goto finish;
            VkCommandBufferBeginInfo begin = {
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
            };
            if (beginCmd(cmd, &begin) != VK_SUCCESS) goto finish;
            resetQuery(cmd, query_pool, 0, 2);
            writeTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, query_pool, 0);
            VkClearValue clear[2] = {
                { .color = { .float32 = {0.02f, 0.03f, 0.055f, 1.0f} } },
                { .depthStencil = {1.0f, 0} }
            };
            VkRenderPassBeginInfo rpBegin = {
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .renderPass = pass, .framebuffer = framebuffer,
                .renderArea = { {0,0}, {CUBE_SIDE,CUBE_SIDE} },
                .clearValueCount = 2, .pClearValues = clear
            };
            cmdBeginPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
            cmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            uint32_t repeats = capture ? 1u : draw_count;
            for (uint32_t drawIndex = 0; drawIndex < repeats; ++drawIndex) {
                float angle = capture ? (0.45f + (float)frame * 0.38f)
                    : 0.65f + (float)drawIndex * 0.047f;
                float params[8] = {
                    cosf(angle), sinf(angle),
                    cosf(angle * 0.75f), sinf(angle * 0.75f),
                    capture ? 1.0f : 0.0f,
                    capture ? 0.0f : (float)shader_steps,
                    (float)columns, (float)instances
                };
                pushConstants(cmd, layout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(params), params);
                draw(cmd, 36, capture ? 1u : instances, 0, 0);
            }
            cmdEndPass(cmd);
            writeTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, query_pool, 1);

            // Copy an actual rendered preview only in untimed warmup frames.
            if (capture) {
                VkBufferImageCopy copy = {
                    .bufferOffset = 0, .bufferRowLength = 0, .bufferImageHeight = 0,
                    .imageSubresource = {
                        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                        .mipLevel = 0, .baseArrayLayer = 0, .layerCount = 1
                    },
                    .imageExtent = {CUBE_SIDE, CUBE_SIDE, 1}
                };
                copyImage(cmd, images[0], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                          read_buffer, 1, &copy);
            }
            if (endCmd(cmd) != VK_SUCCESS) goto finish;
            VkSubmitInfo submission = {
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .commandBufferCount = 1, .pCommandBuffers = &cmd
            };
            error = "Vulkan GPU command submission or completion failed";
            if (submit(queue, 1, &submission, VK_NULL_HANDLE) != VK_SUCCESS) goto finish;
            if (waitIdle(queue) != VK_SUCCESS) goto finish;
            if (frame >= CUBE_WARMUP_FRAMES) {
                uint64_t ticks[2] = {0};
                error = "GPU timestamp query failed";
                if (queryResults(device, query_pool, 0, 2, sizeof(ticks), ticks,
                        sizeof(uint64_t), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT)
                        != VK_SUCCESS) goto finish;
                uint64_t delta = ticks[1] - ticks[0];
                if (timestamp_bits < 64)
                    delta &= (UINT64_MAX >> (64 - timestamp_bits));
                double ms = (double)delta *
                    (double)properties.limits.timestampPeriod / 1000000.0;
                if (!isfinite(ms) || ms <= 0.0 || ms > 4000.0) {
                    error = "Invalid GPU timestamp delta from driver";
                    goto finish;
                }
                gpu_samples[accepted++] = ms;
            }
            if (capture && mapMemory(device, read_memory, 0, VK_WHOLE_SIZE,
                                     0, &pixels) == VK_SUCCESS) {
                mapped_read = true;
                VkMappedMemoryRange range = {
                    .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
                    .memory = read_memory, .offset = 0, .size = VK_WHOLE_SIZE
                };
                if (read_coherent || invalidate(device, 1, &range) == VK_SUCCESS) {
                    jbyteArray preview = (*env)->NewByteArray(env, (jsize)CUBE_BYTES);
                    if (preview) {
                        (*env)->SetByteArrayRegion(env, preview, 0,
                            (jsize)CUBE_BYTES, pixels);
                        (*env)->CallVoidMethod(env, reporter, onFrame, preview,
                            CUBE_SIDE, CUBE_SIDE);
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        (*env)->DeleteLocalRef(env, preview);
                    }
                }
                unmapMemory(device, read_memory);
                mapped_read = false;
                pixels = NULL;
            }
            // Spread repeated queries over a meaningful observation window.
            // GPU timing is measured above, so this delay does not affect FPS.
            const struct timespec pacing = { .tv_sec = 0, .tv_nsec = 16000000L };
            nanosleep(&pacing, NULL);
        }
        if (accepted != CUBE_MEASURED_FRAMES) {
            error = "Incomplete GPU benchmark profile";
            goto finish;
        }
        qsort(gpu_samples, accepted, sizeof(gpu_samples[0]), cube_sort_double);
        gpu_median[profile] = (gpu_samples[accepted/2 - 1] +
                               gpu_samples[accepted/2]) * 0.5;
        gpu_p95[profile] = gpu_samples[accepted * 95 / 100];
    }
    // SUITE3, medians and P95s in ms, sample count, schema, GPU name.
    snprintf(output, sizeof(output),
        "SUITE3\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%u\t%d\t%s",
        gpu_median[0], gpu_p95[0], gpu_median[1], gpu_p95[1],
        gpu_median[2], gpu_p95[2], CUBE_MEASURED_FRAMES,
        CUBE_BENCHMARK_VERSION, properties.deviceName);
    ready = true;

finish:
    if (mapped_read && device && unmapMemory) unmapMemory(device, read_memory);
    if (device && waitIdle && queue) waitIdle(queue);
    if (device && destroyQueryPool && query_pool)
        destroyQueryPool(device, query_pool, NULL);
    if (device && destroyPipeline && pipeline) destroyPipeline(device, pipeline, NULL);
    if (device && destroyLayout && layout) destroyLayout(device, layout, NULL);
    for (int i = 0; i < 2; i++) {
        if (device && destroyShader && shaders[i]) destroyShader(device, shaders[i], NULL);
    }
    if (device && destroyFramebuffer && framebuffer) destroyFramebuffer(device, framebuffer, NULL);
    if (device && destroyPass && pass) destroyPass(device, pass, NULL);
    for (int i = 0; i < 2; i++) {
        if (device && destroyView && views[i]) destroyView(device, views[i], NULL);
        if (device && destroyImage && images[i]) destroyImage(device, images[i], NULL);
        if (device && freeMemory && image_mem[i]) freeMemory(device, image_mem[i], NULL);
    }
    if (device && destroyBuffer && read_buffer) destroyBuffer(device, read_buffer, NULL);
    if (device && freeMemory && read_memory) freeMemory(device, read_memory, NULL);
    if (device && destroyPool && pool) destroyPool(device, pool, NULL);
    if (device && D) D(device, NULL);
    free(families);
    if (lab_instance && destroyInstance) destroyInstance(lab_instance, NULL);
    instance = VK_NULL_HANDLE;
    physicalDevice = VK_NULL_HANDLE;
    if (vulkan_handle) dlclose(vulkan_handle);
    vulkan_handle = NULL;
    if (!ready) snprintf(output, sizeof(output), "ERROR\t%s", error);
    return (*env)->NewStringUTF(env, output);
}
