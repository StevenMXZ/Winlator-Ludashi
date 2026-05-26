#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#include "VulkanRendererContext.h"
#include <stdexcept>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <unistd.h>
#include <sys/socket.h>
#include <poll.h>
#include <errno.h>
#include <dlfcn.h>
#include "window_vert.h"
#include "window_frag.h"

VulkanRendererContext::VulkanRendererContext(ANativeWindow* win, int cW, int cH, void* aHandle)
    : window(win), surfaceWidth(cW), surfaceHeight(cH), containerWidth(cW), containerHeight(cH),
      adrenotoolsHandle(aHandle)
{
    createInstance(); createSurface(); pickPhysicalDevice(); createLogicalDevice();
    createSwapchain(); createRenderPass(); createDSLayout();
    createPipeline(false, pipeline); createCursorPipeline();
    createFramebuffers(); createCmdPool(); createSampler();
    createWinTexPool(); createCursorDS(); createCmdBufs(); createSyncObjects();
    VkFenceCreateInfo ofi{}; ofi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; ofi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vk_.CreateFence(device, &ofi, nullptr, &oneTimeFence) != VK_SUCCESS) {
        throw std::runtime_error("oneTimeFence");
    }
    isRunning = true;
    renderThread = std::thread(&VulkanRendererContext::renderLoop, this);
}

VulkanRendererContext::~VulkanRendererContext() {
    isRunning = false; dirtyCV.notify_all();
    if (renderThread.joinable()) renderThread.join();
    std::lock_guard<std::mutex> lk(renderMutex);
    vk_.DeviceWaitIdle(device);
    for (auto& [id, wt] : texMap) destroyWinTex(wt);
    texMap.clear();
    cleanupSwapchain(); cleanupCursorTex();
    cleanupAllAHBCache();
    vk_.DestroySampler(device, sampler, nullptr);
    vk_.DestroyDescriptorPool(device, winTexPool, nullptr);
    if (cursorPool != VK_NULL_HANDLE) vk_.DestroyDescriptorPool(device, cursorPool, nullptr);
    if (cursorPipe != VK_NULL_HANDLE) vk_.DestroyPipeline(device, cursorPipe, nullptr);
    vk_.DestroyPipeline(device, pipeline, nullptr);
    vk_.DestroyPipelineLayout(device, pipeLayout, nullptr);
    vk_.DestroyDescriptorSetLayout(device, dsLayout, nullptr);
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        vk_.DestroySemaphore(device, renderDoneSems[i], nullptr);
        vk_.DestroySemaphore(device, imgAvailSems[i], nullptr);
        vk_.DestroyFence(device, inFlightFences[i], nullptr);
    }
    if (scanoutBlitFence != VK_NULL_HANDLE) {
        vk_.WaitForFences(device, 1, &scanoutBlitFence, VK_TRUE, UINT64_MAX);
        vk_.DestroyFence(device, scanoutBlitFence, nullptr);
        scanoutBlitFence = VK_NULL_HANDLE;
    }
    if (scanoutBlitCb != VK_NULL_HANDLE) {
        vk_.FreeCommandBuffers(device, cmdPool, 1, &scanoutBlitCb);
        scanoutBlitCb = VK_NULL_HANDLE;
    }
    if (oneTimeFence != VK_NULL_HANDLE) {
        vk_.DestroyFence(device, oneTimeFence, nullptr);
        oneTimeFence = VK_NULL_HANDLE;
    }
    vk_.DestroyCommandPool(device, cmdPool, nullptr);
    vk_.DestroyRenderPass(device, renderPass, nullptr);
    vk_.DestroyDevice(device, nullptr);
    vk_.DestroySurfaceKHR(instance, surface, nullptr);
    vk_.DestroyInstance(instance, nullptr);
    if (adrenotoolsHandle) { dlclose(adrenotoolsHandle); adrenotoolsHandle = nullptr; }
}

void VulkanRendererContext::loadInstanceDispatch() {
    auto i = [&](const char* name) { return gipa ? gipa(instance, name) : nullptr; };
#define LOAD_I2(fn) vk_.fn = (PFN_vk##fn)i("vk"#fn)
    LOAD_I2(DestroyInstance);
    LOAD_I2(EnumeratePhysicalDevices);
    LOAD_I2(GetPhysicalDeviceProperties);
    LOAD_I2(GetPhysicalDeviceMemoryProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    LOAD_I2(GetPhysicalDeviceSurfaceFormatsKHR);
    LOAD_I2(GetPhysicalDeviceSurfacePresentModesKHR);
    LOAD_I2(GetPhysicalDeviceQueueFamilyProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceSupportKHR);
    LOAD_I2(CreateDevice);
    LOAD_I2(DestroySurfaceKHR);
    LOAD_I2(CreateAndroidSurfaceKHR);
    LOAD_I2(GetDeviceProcAddr);
}
void VulkanRendererContext::loadDeviceDispatch() {
    auto d = [&](const char* name) -> PFN_vkVoidFunction {
        return vk_.GetDeviceProcAddr ? vk_.GetDeviceProcAddr(device, name) : nullptr;
    };
#define LOAD_D2(fn) vk_.fn = (PFN_vk##fn)d("vk"#fn)
    LOAD_D2(DestroyDevice);
    LOAD_D2(GetDeviceQueue);
    LOAD_D2(DeviceWaitIdle);
    LOAD_D2(CreateSwapchainKHR);
    LOAD_D2(DestroySwapchainKHR);
    LOAD_D2(GetSwapchainImagesKHR);
    LOAD_D2(AcquireNextImageKHR);
    LOAD_D2(QueuePresentKHR);
    /* VK_GOOGLE_display_timing function pointers. Both are optional —
     * displayTimingSupported gates whether they're used. The lookups
     * return nullptr on devices without the extension; we still call
     * LOAD_D2 unconditionally so the table is consistent. */
    LOAD_D2(GetPastPresentationTimingGOOGLE);
    LOAD_D2(GetRefreshCycleDurationGOOGLE);
    LOAD_D2(QueueSubmit);
    LOAD_D2(CreateRenderPass);
    LOAD_D2(DestroyRenderPass);
    LOAD_D2(CreateFramebuffer);
    LOAD_D2(DestroyFramebuffer);
    LOAD_D2(CreateImageView);
    LOAD_D2(DestroyImageView);
    LOAD_D2(CreateImage);
    LOAD_D2(DestroyImage);
    LOAD_D2(CreateBuffer);
    LOAD_D2(DestroyBuffer);
    LOAD_D2(AllocateMemory);
    LOAD_D2(FreeMemory);
    LOAD_D2(MapMemory);
    LOAD_D2(FlushMappedMemoryRanges);
    LOAD_D2(BindBufferMemory);
    LOAD_D2(BindImageMemory);
    LOAD_D2(GetBufferMemoryRequirements);
    LOAD_D2(GetImageMemoryRequirements);
    LOAD_D2(CreateDescriptorSetLayout);
    LOAD_D2(DestroyDescriptorSetLayout);
    LOAD_D2(CreateDescriptorPool);
    LOAD_D2(DestroyDescriptorPool);
    LOAD_D2(AllocateDescriptorSets);
    LOAD_D2(FreeDescriptorSets);
    LOAD_D2(UpdateDescriptorSets);
    LOAD_D2(CreatePipelineLayout);
    LOAD_D2(DestroyPipelineLayout);
    LOAD_D2(CreateShaderModule);
    LOAD_D2(DestroyShaderModule);
    LOAD_D2(CreateGraphicsPipelines);
    LOAD_D2(DestroyPipeline);
    LOAD_D2(CreateCommandPool);
    LOAD_D2(DestroyCommandPool);
    LOAD_D2(AllocateCommandBuffers);
    LOAD_D2(FreeCommandBuffers);
    LOAD_D2(BeginCommandBuffer);
    LOAD_D2(EndCommandBuffer);
    LOAD_D2(ResetCommandBuffer);
    LOAD_D2(CmdBeginRenderPass);
    LOAD_D2(CmdEndRenderPass);
    LOAD_D2(CmdBindPipeline);
    LOAD_D2(CmdBindDescriptorSets);
    LOAD_D2(CmdDraw);
    LOAD_D2(CmdPushConstants);
    LOAD_D2(CmdSetViewport);
    LOAD_D2(CmdSetScissor);
    LOAD_D2(CmdPipelineBarrier);
    LOAD_D2(CmdCopyImage);
    LOAD_D2(CmdBlitImage);
    LOAD_D2(CmdCopyBufferToImage);
    LOAD_D2(CreateSampler);
    LOAD_D2(DestroySampler);
    LOAD_D2(CreateSemaphore);
    LOAD_D2(DestroySemaphore);
    LOAD_D2(CreateFence);
    LOAD_D2(DestroyFence);
    LOAD_D2(WaitForFences);
    LOAD_D2(ResetFences);
    LOAD_D2(GetFenceStatus);

    vk_.GetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)d("vkGetAndroidHardwareBufferPropertiesANDROID");
}

void VulkanRendererContext::createInstance() {
    RLOG("createInstance: adrenotoolsHandle=%p (custom driver %s)",
        adrenotoolsHandle, adrenotoolsHandle?"ACTIVE":"NOT SET - using stock driver");

    if (adrenotoolsHandle) {
        gipa = (PFN_vkGetInstanceProcAddr)dlsym(adrenotoolsHandle, "vkGetInstanceProcAddr");
    }
    if (!gipa) {
        void* loaderLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL);
        if (loaderLib)
            gipa = (PFN_vkGetInstanceProcAddr)dlsym(loaderLib, "vkGetInstanceProcAddr");
    }

    vk_.CreateInstance = (PFN_vkCreateInstance)gipa(nullptr, "vkCreateInstance");
    VkApplicationInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;
    ai.pApplicationName="Winlator"; ai.apiVersion=VK_API_VERSION_1_3;
    const char* ext[]={"VK_KHR_surface","VK_KHR_android_surface"};
    VkInstanceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo=&ai; ci.enabledExtensionCount=2; ci.ppEnabledExtensionNames=ext;
    if (vk_.CreateInstance(&ci,nullptr,&instance)!=VK_SUCCESS) throw std::runtime_error("instance");

    loadInstanceDispatch();
}
void VulkanRendererContext::createSurface() {
    VkAndroidSurfaceCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window=window;
    if (vk_.CreateAndroidSurfaceKHR(instance,&ci,nullptr,&surface)!=VK_SUCCESS) throw std::runtime_error("surface");
}
void VulkanRendererContext::pickPhysicalDevice() {
    uint32_t n=0; vk_.EnumeratePhysicalDevices(instance,&n,nullptr);
    std::vector<VkPhysicalDevice> devs(n); vk_.EnumeratePhysicalDevices(instance,&n,devs.data());
    physicalDevice = VK_NULL_HANDLE;
    graphicsQueueFamilyIndex = 0;
    for (auto d : devs) {
        uint32_t qCount = 0;
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, qProps.data());
        for (uint32_t i = 0; i < qCount; i++) {
            VkBool32 present = VK_FALSE;
            vk_.GetPhysicalDeviceSurfaceSupportKHR(d, i, surface, &present);
            if ((qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                physicalDevice = d;
                graphicsQueueFamilyIndex = i;
                return;
            }
        }
    }
    if (n > 0) physicalDevice = devs[0];
}
void VulkanRendererContext::createLogicalDevice() {
    float p=1.f;
    VkDeviceQueueCreateInfo qi{}; qi.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qi.queueFamilyIndex=graphicsQueueFamilyIndex; qi.queueCount=1; qi.pQueuePriorities=&p;

    PFN_vkEnumerateDeviceExtensionProperties enumDevExts =
        (PFN_vkEnumerateDeviceExtensionProperties)gipa(instance, "vkEnumerateDeviceExtensionProperties");
    { uint32_t n=0; if(enumDevExts) enumDevExts(physicalDevice,nullptr,&n,nullptr);
      std::vector<VkExtensionProperties> av(n);
      if(enumDevExts) enumDevExts(physicalDevice,nullptr,&n,av.data());
      for (auto& e:av) {
          if (strcmp(e.extensionName,"VK_EXT_filter_cubic")==0
           || strcmp(e.extensionName,"VK_IMG_filter_cubic")==0) cubicSupported=true;
          /* VK_GOOGLE_display_timing: enables vkGetPastPresentationTimingGOOGLE
           * which returns the CLOCK_MONOTONIC actualPresentTime for past
           * presents. Used to measure accurate Native (X11) compositor
           * latency — the previous T2 hook (QueuePresent-return) fires
           * before SF has displayed the frame, biasing the reading low. */
          if (strcmp(e.extensionName, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME)==0)
              displayTimingSupported = true;
      } }
    std::vector<const char*> extList = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
    };
    if (cubicSupported) extList.push_back("VK_EXT_filter_cubic");
    if (displayTimingSupported) extList.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
    RLOG("createLogicalDevice: VK_GOOGLE_display_timing %s (LAT HUD T2: %s)",
         displayTimingSupported ? "ENABLED" : "not available",
         displayTimingSupported
            ? "actual displayed-at time (accurate)"
            : "QueuePresent-return (under-reports by ~1 vsync)");
    VkDeviceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    ci.pQueueCreateInfos=&qi; ci.queueCreateInfoCount=1;
    ci.enabledExtensionCount=(uint32_t)extList.size(); ci.ppEnabledExtensionNames=extList.data();
    if (vk_.CreateDevice(physicalDevice,&ci,nullptr,&device)!=VK_SUCCESS) throw std::runtime_error("device");
    vk_.GetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)gipa(instance, "vkGetDeviceProcAddr");
    loadDeviceDispatch();
    vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);

    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice, &props);
    maxAnisotropy = props.limits.maxSamplerAnisotropy;
}
void VulkanRendererContext::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice,surface,&caps);
    swapchainExt=(caps.currentExtent.width!=0xFFFFFFFF)?caps.currentExtent:VkExtent2D{(uint32_t)surfaceWidth,(uint32_t)surfaceHeight};
    uint32_t fmtN=0; vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fmtN); vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,fmts.data());
    swapchainFmt = VK_FORMAT_R8G8B8A8_UNORM;
    uint32_t imgCount=caps.minImageCount+1;

    uint32_t pmCount=0;
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,nullptr);
    availablePresentModes.resize(pmCount);
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,availablePresentModes.data());
    VkPresentModeKHR presentMode=VK_PRESENT_MODE_FIFO_KHR;
    for (auto pm:availablePresentModes) if(pm==requestedPresentMode){presentMode=pm;break;}

    const char* swapchainEnv = std::getenv("RENDERER_SWAPCHAIN");
    uint32_t swapchainExtra = 0;
    if (swapchainEnv && *swapchainEnv) {
        int parsed = std::atoi(swapchainEnv);
        if (parsed > 0) swapchainExtra = (uint32_t)parsed;
    }
    imgCount += swapchainExtra;

    if (presentMode == VK_PRESENT_MODE_MAILBOX_KHR && imgCount < 3) imgCount = 3;
    if (imgCount < caps.minImageCount) imgCount = caps.minImageCount;
    if (caps.maxImageCount > 0 && imgCount > caps.maxImageCount) imgCount = caps.maxImageCount;
    if(verboseLog){
        std::string pmList;
        for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
        RLOG("createSwapchain: %dx%d fmt=%d supportedPresentModes=[%s] chosen=%d req=%d",
            swapchainExt.width,swapchainExt.height,(int)swapchainFmt,pmList.c_str(),(int)presentMode,(int)requestedPresentMode);
    }

    VkSurfaceTransformFlagBitsKHR pre=
        (caps.supportedTransforms&VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)?
        VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:caps.currentTransform;

    VkCompositeAlphaFlagBitsKHR compositeAlpha=
        (caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

    VkSwapchainKHR oldSwapchain=swapchain;
    VkSwapchainCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    ci.surface=surface; ci.minImageCount=imgCount; ci.imageFormat=swapchainFmt;
    ci.imageColorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR; ci.imageExtent=swapchainExt;
    ci.imageArrayLayers=1; ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; ci.preTransform=pre;
    ci.compositeAlpha=compositeAlpha; ci.presentMode=presentMode; ci.clipped=VK_TRUE;
    ci.oldSwapchain=oldSwapchain;
    if (vk_.CreateSwapchainKHR(device,&ci,nullptr,&swapchain)!=VK_SUCCESS) throw std::runtime_error("swapchain");
    RLOG("swapchain created: %dx%d format=%d presentMode=%d compositeAlpha=%d imageCount=%u extra=%u",
        swapchainExt.width,swapchainExt.height,swapchainFmt,(int)presentMode,(int)compositeAlpha,imgCount,swapchainExtra);
    if (oldSwapchain!=VK_NULL_HANDLE) vk_.DestroySwapchainKHR(device,oldSwapchain,nullptr);
    vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,nullptr);
    swapchainImages.resize(imgCount); vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,swapchainImages.data());
    swapchainViews.resize(imgCount);
    for (size_t i=0;i<imgCount;i++) {
        VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image=swapchainImages[i]; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=swapchainFmt;
        vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        VkComponentMapping mapping{};
        mapping.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        vi.components = mapping;
        if (vk_.CreateImageView(device,&vi,nullptr,&swapchainViews[i])!=VK_SUCCESS) throw std::runtime_error("imgview");
    }
}
void VulkanRendererContext::createRenderPass() {
    VkAttachmentDescription att{}; att.format=swapchainFmt; att.samples=VK_SAMPLE_COUNT_1_BIT;
    att.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount=1; sub.pColorAttachments=&ref;
    VkSubpassDependency dep{}; dep.srcSubpass=VK_SUBPASS_EXTERNAL; dep.dstSubpass=0;
    dep.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; dep.srcAccessMask=0;
    dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount=1; ci.pAttachments=&att; ci.subpassCount=1; ci.pSubpasses=&sub;
    ci.dependencyCount=1; ci.pDependencies=&dep;
    if (vk_.CreateRenderPass(device,&ci,nullptr,&renderPass)!=VK_SUCCESS) throw std::runtime_error("renderpass");
}
void VulkanRendererContext::createDSLayout() {
    VkDescriptorSetLayoutBinding b{}; b.binding=0; b.descriptorCount=1;
    b.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount=1; ci.pBindings=&b;
    if (vk_.CreateDescriptorSetLayout(device,&ci,nullptr,&dsLayout)!=VK_SUCCESS) throw std::runtime_error("dslayout");
}
VkShaderModule VulkanRendererContext::makeShader(const uint32_t* code, size_t sz) {
    VkShaderModuleCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize=sz; ci.pCode=code; VkShaderModule m;
    if (vk_.CreateShaderModule(device,&ci,nullptr,&m)!=VK_SUCCESS) throw std::runtime_error("shader");
    return m;
}
void VulkanRendererContext::createPipeline(bool blend, VkPipeline& out) {
    if (pipeLayout==VK_NULL_HANDLE) {
        VkPushConstantRange pc{}; pc.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;
        pc.size=sizeof(WindowPushConstants);
        VkPipelineLayoutCreateInfo li{}; li.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        li.setLayoutCount=1; li.pSetLayouts=&dsLayout; li.pushConstantRangeCount=1; li.pPushConstantRanges=&pc;
        if (vk_.CreatePipelineLayout(device,&li,nullptr,&pipeLayout)!=VK_SUCCESS) throw std::runtime_error("pipelayout");
    }
    auto vert=makeShader(window_vert_code,sizeof(window_vert_code));
    auto frag=makeShader(window_frag_code,sizeof(window_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=blend?VK_TRUE:VK_FALSE;
    if (blend){ba.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA;ba.dstColorBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;ba.colorBlendOp=VK_BLEND_OP_ADD;ba.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE;ba.dstAlphaBlendFactor=VK_BLEND_FACTOR_ZERO;ba.alphaBlendOp=VK_BLEND_OP_ADD;}
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=renderPass; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&out)!=VK_SUCCESS) throw std::runtime_error("pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
}
void VulkanRendererContext::createCursorPipeline() { createPipeline(true, cursorPipe); }
void VulkanRendererContext::createFramebuffers() {
    swapchainFBs.resize(swapchainViews.size());
    for (size_t i=0;i<swapchainViews.size();i++) {
        VkImageView att[]={swapchainViews[i]};
        VkFramebufferCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass=renderPass; fi.attachmentCount=1; fi.pAttachments=att;
        fi.width=swapchainExt.width; fi.height=swapchainExt.height; fi.layers=1;
        if (vk_.CreateFramebuffer(device,&fi,nullptr,&swapchainFBs[i])!=VK_SUCCESS) throw std::runtime_error("fb");
    }
}
void VulkanRendererContext::createCmdPool() {
    VkCommandPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    ci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; ci.queueFamilyIndex=graphicsQueueFamilyIndex;
    if (vk_.CreateCommandPool(device,&ci,nullptr,&cmdPool)!=VK_SUCCESS) throw std::runtime_error("cmdpool");
}
void VulkanRendererContext::createSampler() {
    bool useCubic = (filterMode == 2) && cubicSupported;
    VkFilter filter = (filterMode == 1) ? VK_FILTER_NEAREST
                    : (useCubic)         ? VK_FILTER_CUBIC_EXT
                    :                      VK_FILTER_LINEAR;
    RLOG("createSampler: filter=%s (filterMode=%d, cubicSupported=%d)",
        filterMode==2?(cubicSupported?"CUBIC":"LINEAR_FALLBACK"):filterMode==1?"NEAREST":"LINEAR",
        filterMode, (int)cubicSupported);
    VkSamplerCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    ci.magFilter=filter; ci.minFilter=filter;
    ci.addressModeU=ci.addressModeV=ci.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    ci.minLod=0.f; ci.maxLod=0.f;
    if (vk_.CreateSampler(device,&ci,nullptr,&sampler)!=VK_SUCCESS) throw std::runtime_error("sampler");
}
void VulkanRendererContext::createWinTexPool() {
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,512};
    VkDescriptorPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.flags=VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    ci.poolSizeCount=1; ci.pPoolSizes=&ps; ci.maxSets=512;
    if (vk_.CreateDescriptorPool(device,&ci,nullptr,&winTexPool)!=VK_SUCCESS) throw std::runtime_error("wintexpool");
}
void VulkanRendererContext::createCursorDS() {
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1};
    VkDescriptorPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.poolSizeCount=1; ci.pPoolSizes=&ps; ci.maxSets=1;
    if (vk_.CreateDescriptorPool(device,&ci,nullptr,&cursorPool)!=VK_SUCCESS) throw std::runtime_error("cursorpool");
    VkDescriptorSetAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool=cursorPool; ai.descriptorSetCount=1; ai.pSetLayouts=&dsLayout;
    vk_.AllocateDescriptorSets(device,&ai,&cursorDS);
}
void VulkanRendererContext::createCmdBufs() {
    cmdBufs.resize(MAX_FRAMES_IN_FLIGHT);
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool=cmdPool; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=MAX_FRAMES_IN_FLIGHT;
    if (vk_.AllocateCommandBuffers(device,&ai,cmdBufs.data())!=VK_SUCCESS) throw std::runtime_error("cmdbuf");
}
void VulkanRendererContext::createSyncObjects() {
    imgAvailSems.resize(MAX_FRAMES_IN_FLIGHT); renderDoneSems.resize(MAX_FRAMES_IN_FLIGHT); inFlightFences.resize(MAX_FRAMES_IN_FLIGHT);
    VkSemaphoreCreateInfo si{}; si.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
    for (uint32_t i=0;i<MAX_FRAMES_IN_FLIGHT;i++) {
        if (vk_.CreateSemaphore(device,&si,nullptr,&imgAvailSems[i])!=VK_SUCCESS||
            vk_.CreateSemaphore(device,&si,nullptr,&renderDoneSems[i])!=VK_SUCCESS||
            vk_.CreateFence(device,&fi,nullptr,&inFlightFences[i])!=VK_SUCCESS) throw std::runtime_error("sync");
    }
}
void VulkanRendererContext::cleanupSwapchain() {
    for (auto fb:swapchainFBs) vk_.DestroyFramebuffer(device,fb,nullptr); swapchainFBs.clear();
    for (auto iv:swapchainViews) vk_.DestroyImageView(device,iv,nullptr); swapchainViews.clear();
    if (!cmdBufs.empty()){vk_.FreeCommandBuffers(device,cmdPool,(uint32_t)cmdBufs.size(),cmdBufs.data());cmdBufs.clear();}
    if (swapchain!=VK_NULL_HANDLE) { vk_.DestroySwapchainKHR(device,swapchain,nullptr); swapchain=VK_NULL_HANDLE; }
}

uint32_t VulkanRendererContext::findMemType(uint32_t filter, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mp; vk_.GetPhysicalDeviceMemoryProperties(physicalDevice,&mp);
    for (uint32_t i=0;i<mp.memoryTypeCount;i++)
        if ((filter&(1u<<i))&&(mp.memoryTypes[i].propertyFlags&props)==props) return i;
    throw std::runtime_error("memtype");
}
void VulkanRendererContext::createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
    VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem)
{
    VkBufferCreateInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO; bi.size=sz; bi.usage=usage; bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateBuffer(device,&bi,nullptr,&buf)!=VK_SUCCESS) throw std::runtime_error("buffer");
    VkMemoryRequirements req; vk_.GetBufferMemoryRequirements(device,buf,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,props);
    if (vk_.AllocateMemory(device,&ai,nullptr,&mem)!=VK_SUCCESS) throw std::runtime_error("bufmem");
    vk_.BindBufferMemory(device,buf,mem,0);
}
VkCommandBuffer VulkanRendererContext::beginOneTime() {
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandPool=cmdPool; ai.commandBufferCount=1;
    VkCommandBuffer cb; vk_.AllocateCommandBuffers(device,&ai,&cb);
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk_.BeginCommandBuffer(cb,&bi); return cb;
}
void VulkanRendererContext::endOneTime(VkCommandBuffer cb) {
    vk_.EndCommandBuffer(cb);
    vk_.WaitForFences(device, 1, &oneTimeFence, VK_TRUE, UINT64_MAX);
    vk_.ResetFences(device, 1, &oneTimeFence);
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO; si.commandBufferCount=1; si.pCommandBuffers=&cb;
    vk_.QueueSubmit(graphicsQueue,1,&si,oneTimeFence);
    vk_.WaitForFences(device,1,&oneTimeFence,VK_TRUE,UINT64_MAX);
    vk_.FreeCommandBuffers(device,cmdPool,1,&cb);
}
void VulkanRendererContext::transition(VkCommandBuffer cb, VkImage img,
    VkImageLayout ol, VkImageLayout nl, VkAccessFlags sa, VkAccessFlags da,
    VkPipelineStageFlags ss, VkPipelineStageFlags ds)
{
    VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout=ol; b.newLayout=nl; b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    b.image=img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; b.srcAccessMask=sa; b.dstAccessMask=da;
    vk_.CmdPipelineBarrier(cb,ss,ds,0,0,nullptr,0,nullptr,1,&b);
}

bool VulkanRendererContext::createWinTexResources(WinTex& wt, int w, int h) {
    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=(swapRB) ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS) return false;
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,wt.img,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device,&ai,nullptr,&wt.mem)!=VK_SUCCESS){vk_.DestroyImage(device,wt.img,nullptr);wt.img=VK_NULL_HANDLE;return false;}
    vk_.BindImageMemory(device,wt.img,wt.mem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=(swapRB) ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS){destroyWinTex(wt);return false;}
    VkDescriptorSetAllocateInfo dsai{}; dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO; dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
    VkResult dsRes = vk_.AllocateDescriptorSets(device,&dsai,&wt.ds);
    if (dsRes==VK_ERROR_OUT_OF_POOL_MEMORY) {
        RLOG_E("createWinTexResources: descriptor pool exhausted for window texture");
        destroyWinTex(wt);
        return false;
    }
    if (dsRes!=VK_SUCCESS){destroyWinTex(wt);return false;}
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=wt.view; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=wt.ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    VkDeviceSize stgSz=(VkDeviceSize)w*h*4;
    createBuffer(stgSz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,wt.stg,wt.stgMem);
    vk_.MapMemory(device,wt.stgMem,0,stgSz,0,&wt.mapped);
    wt.cap=stgSz; wt.w=w; wt.h=h; wt.needsTransition=true;
    return true;
}

bool VulkanRendererContext::importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb, VkFormat overrideFormat) {

    if (!vk_.GetAndroidHardwareBufferPropertiesANDROID)
    	return false;

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtP{};
    fmtP.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext=&fmtP;
    if (vk_.GetAndroidHardwareBufferPropertiesANDROID(device,ahb,&props)!=VK_SUCCESS)
    	return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb,&desc);

    /* If caller supplied an explicit format (e.g. direct-render scanout
     * needs B8G8R8A8 to match DXVK's written byte order), use it; else
     * fall back to the swapRB-based default. */
    VkFormat use_format = overrideFormat;
    if (use_format == VK_FORMAT_UNDEFINED)
        use_format = (swapRB) ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM;

    VkExternalFormatANDROID ef{};
    ef.sType=VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    ef.externalFormat=use_format;

    VkExternalMemoryImageCreateInfo emi{};
    emi.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emi.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    ef.pNext=const_cast<void*>(emi.pNext);
    emi.pNext=&ef;

    VkImageCreateInfo ii{};
    ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.pNext=&emi; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.format=use_format;
    ii.extent={desc.width,desc.height,1};
    ii.mipLevels=1; 
    ii.arrayLayers=1; 
    ii.samples=VK_SAMPLE_COUNT_1_BIT; 
    ii.tiling=VK_IMAGE_TILING_OPTIMAL;
    ii.usage=VK_IMAGE_USAGE_SAMPLED_BIT; 
    ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE; 
    ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;

    if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS) 
    	return false;
    	
    VkImportAndroidHardwareBufferInfoANDROID imp{}; 
    imp.sType=VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID; 
    imp.buffer=ahb;
    
    VkMemoryDedicatedAllocateInfo ded{}; 
    ded.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO; 
    ded.pNext=&imp; 
    ded.image=wt.img;
    
    VkMemoryAllocateInfo mai{}; 
    mai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; 
    mai.pNext=&ded; 
    mai.allocationSize=props.allocationSize; 
    mai.memoryTypeIndex=findMemType(props.memoryTypeBits,0);
    if (vk_.AllocateMemory(device,&mai,nullptr,&wt.mem)!=VK_SUCCESS){
    	vk_.DestroyImage(device,wt.img,nullptr);
    	wt.img=VK_NULL_HANDLE;
    	return false;
    }
    
    vk_.BindImageMemory(device,wt.img,wt.mem,0);
    VkExternalFormatANDROID vef{}; 
    vef.sType=VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID; 
    vef.externalFormat=(swapRB)?VK_FORMAT_R8G8B8A8_UNORM:VK_FORMAT_B8G8R8A8_UNORM;

    VkImageViewCreateInfo vi{}; 
    vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; 
    vi.pNext=&vef;
    vi.image=wt.img; 
    vi.viewType=VK_IMAGE_VIEW_TYPE_2D; 
    vi.format=(swapRB)?VK_FORMAT_R8G8B8A8_UNORM:VK_FORMAT_B8G8R8A8_UNORM;
    vi.components={VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY};
    vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS){
    	destroyWinTex(wt);
    	return false;
    }
    
    VkDescriptorSetAllocateInfo dsai{}; 
    dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO; 
    dsai.descriptorPool=winTexPool; 
    dsai.descriptorSetCount=1; 
    dsai.pSetLayouts=&dsLayout;
    VkResult dsRes = vk_.AllocateDescriptorSets(device,&dsai,&wt.ds);
    if (dsRes==VK_ERROR_OUT_OF_POOL_MEMORY) {
        RLOG_E("importAHBToWinTex: descriptor pool exhausted for AHB texture");
        destroyWinTex(wt);
        return false;
    }
    if (dsRes!=VK_SUCCESS){
	    destroyWinTex(wt);
  		return false;
  	}
    VkDescriptorImageInfo dii{}; 
    dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; 
    dii.imageView=wt.view; 
    dii.sampler=sampler;
    
    VkWriteDescriptorSet wr{}; 
    wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; 
    wr.dstSet=wt.ds; 
    wr.dstBinding=0; 
    wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; 
    wr.descriptorCount=1; 
    wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    wt.needsTransition = true;
    wt.isAHB=true;
    wt.w=(int)desc.width; 
    wt.h=(int)desc.height;
    
    return true;
}

void VulkanRendererContext::destroyWinTex(WinTex& wt) {
    if (wt.img!=VK_NULL_HANDLE || wt.stg!=VK_NULL_HANDLE) {
        deleteQueue.push_back(wt);
    }
    wt={};
}

void VulkanRendererContext::ensureCursorTex(short w, short h) {
    if (cursorImg!=VK_NULL_HANDLE && cursorTexW==w && cursorTexH==h) return;
    cleanupCursorTex();
    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    vk_.CreateImage(device,&ii,nullptr,&cursorImg);
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,cursorImg,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vk_.AllocateMemory(device,&ai,nullptr,&cursorMem); vk_.BindImageMemory(device,cursorImg,cursorMem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=cursorImg; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vk_.CreateImageView(device,&vi,nullptr,&cursorView);
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=cursorView; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=cursorDS; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    auto cb=beginOneTime();
    transition(cb,cursorImg,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        0,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    endOneTime(cb);
    cursorTexW=w; cursorTexH=h;
}
void VulkanRendererContext::cleanupCursorTex() {
    if (cursorView!=VK_NULL_HANDLE){vk_.DestroyImageView(device,cursorView,nullptr);cursorView=VK_NULL_HANDLE;}
    if (cursorImg!=VK_NULL_HANDLE){vk_.DestroyImage(device,cursorImg,nullptr);cursorImg=VK_NULL_HANDLE;}
    if (cursorMem!=VK_NULL_HANDLE){vk_.FreeMemory(device,cursorMem,nullptr);cursorMem=VK_NULL_HANDLE;}
    if (cursorStg!=VK_NULL_HANDLE){vk_.DestroyBuffer(device,cursorStg,nullptr);vk_.FreeMemory(device,cursorStgM,nullptr);cursorStg=VK_NULL_HANDLE;cursorStgP=nullptr;cursorStgC=0;}
    cursorTexW=0; cursorTexH=0;
}
void VulkanRendererContext::ensureCursorStaging(VkDeviceSize sz) {
    if (cursorStgC>=sz) return;
    if (cursorStg!=VK_NULL_HANDLE){vk_.DestroyBuffer(device,cursorStg,nullptr);vk_.FreeMemory(device,cursorStgM,nullptr);}
    createBuffer(sz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,cursorStg,cursorStgM);
    vk_.MapMemory(device,cursorStgM,0,sz,0,&cursorStgP); cursorStgC=sz;
}

void VulkanRendererContext::recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
    const std::vector<DrawEntry>& draws,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short ptrX, short ptrY, short curHotX, short curHotY,
    short curW, short curH, bool curVis)
{
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb,&bi)!=VK_SUCCESS) throw std::runtime_error("begin cb");

    std::vector<VkImageMemoryBarrier> preLinear, preOptimal, postOptimal;
    preLinear.reserve(draws.size());
    preOptimal.reserve(draws.size());
    postOptimal.reserve(draws.size() + (hasCursorUpload ? 1 : 0));
    for (auto& d:draws) {
        if (d.img==VK_NULL_HANDLE) continue;
        if (d.useLinear && (d.needsTransition || d.dirtyLinear)) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=d.needsTransition?VK_IMAGE_LAYOUT_PREINITIALIZED:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            preLinear.push_back(b);
        } else if (d.isAHB && d.dirtyAHB) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            preLinear.push_back(b);
        } else if (!d.useLinear && (d.needsTransition || d.upload!=VK_NULL_HANDLE) && d.img!=VK_NULL_HANDLE) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED;
            b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=0;
            b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            preOptimal.push_back(b);
            b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            postOptimal.push_back(b);
        }
    }
    if (!preLinear.empty())
        vk_.CmdPipelineBarrier(cb,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,0,nullptr,0,nullptr,(uint32_t)preLinear.size(),preLinear.data());
    if (!preOptimal.empty())
        vk_.CmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,0,nullptr,0,nullptr,(uint32_t)preOptimal.size(),preOptimal.data());
    for (auto& d:draws) {
        if (d.useLinear||d.upload==VK_NULL_HANDLE||d.img==VK_NULL_HANDLE) continue;
        VkBufferImageCopy r{}; r.bufferOffset=0; r.bufferRowLength=0; r.bufferImageHeight=0;
        r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)d.w,(uint32_t)d.h,1};
        vk_.CmdCopyBufferToImage(cb,d.upload,d.img,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&r);
    }
    bool hasCursorCopy=hasCursorUpload&&cursorImg!=VK_NULL_HANDLE&&cursorUpload!=VK_NULL_HANDLE;
    if (hasCursorCopy) {
        VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        b.image=cursorImg; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        b.srcAccessMask=VK_ACCESS_SHADER_READ_BIT; b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,0,nullptr,0,nullptr,1,&b);
        VkBufferImageCopy r{}; r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)curW,(uint32_t)curH,1};
        vk_.CmdCopyBufferToImage(cb,cursorUpload,cursorImg,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&r);
        b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        postOptimal.push_back(b);
    }
    if (!postOptimal.empty())
        vk_.CmdPipelineBarrier(cb,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,0,nullptr,0,nullptr,(uint32_t)postOptimal.size(),postOptimal.data());

    VkRenderPassBeginInfo rpi{}; rpi.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass=renderPass; rpi.framebuffer=swapchainFBs[imgIdx]; rpi.renderArea={{0,0},swapchainExt};
    VkClearValue clr={{{0.f,0.f,0.f,1.f}}}; rpi.clearValueCount=1; rpi.pClearValues=&clr;

    vk_.CmdBeginRenderPass(cb,&rpi,VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0,0,(float)swapchainExt.width,(float)swapchainExt.height,0,1};
    vk_.CmdSetViewport(cb,0,1,&vp);
    VkRect2D sc{{0,0},swapchainExt}; vk_.CmdSetScissor(cb,0,1,&sc);

    vk_.CmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
    for (auto& d:draws) {
        if (d.ds==VK_NULL_HANDLE) continue;
        vk_.CmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeLayout,0,1,&d.ds,0,nullptr);
        WindowPushConstants pc{};
        pc.ndcX0=(ox+(float)d.x*sx)/cw*2.f-1.f;
        pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f;
        pc.ndcX1=(ox+(float)(d.x+d.w)*sx)/cw*2.f-1.f;
        pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
        pc.effectId = activeEffectId;
        pc.sharpness = activeSharpness;
        pc.resW = (float)std::max(1, d.w);
        pc.resH = (float)std::max(1, d.h);
        vk_.CmdPushConstants(cb,pipeLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(pc),&pc);
        vk_.CmdDraw(cb,4,1,0,0);
    }

    if (curVis && cursorImg!=VK_NULL_HANDLE && cursorDS!=VK_NULL_HANDLE) {
        vk_.CmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,cursorPipe);
        vk_.CmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeLayout,0,1,&cursorDS,0,nullptr);
        float cx=(float)std::max(0,(int)ptrX-curHotX), cy=(float)std::max(0,(int)ptrY-curHotY);
        WindowPushConstants cpc{};
        cpc.ndcX0=(ox+cx*sx)/cw*2.f-1.f; cpc.ndcY0=(oy+cy*sy)/ch*2.f-1.f;
        cpc.ndcX1=(ox+(cx+curW)*sx)/cw*2.f-1.f; cpc.ndcY1=(oy+(cy+curH)*sy)/ch*2.f-1.f;
        cpc.effectId = 0;
        cpc.sharpness = 0.5f;
        cpc.resW = (float)std::max(1, (int)curW);
        cpc.resH = (float)std::max(1, (int)curH);
        vk_.CmdPushConstants(cb,pipeLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(cpc),&cpc);
        vk_.CmdDraw(cb,4,1,0,0);
    }
    vk_.CmdEndRenderPass(cb);
    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus!=VK_SUCCESS) {
        RLOG_E("recordCmdBuf: EndCommandBuffer failed with status=%d (swapRB=%d draws=%zu imgIdx=%u)",
            (int)endStatus, (int)swapRB, draws.size(), imgIdx);
        throw std::runtime_error("end cb");
    }
}

void VulkanRendererContext::renderLoop() {

    while (isRunning) {
        { std::unique_lock<std::mutex> lk(dirtyMutex);
          dirtyCV.wait_for(lk,std::chrono::milliseconds(2),[this]{
              return !isRunning||(!surfaceDetached.load()&&(needsRender.load()||fbResized.load()))||cursorMoved.load(); }); }
        if (!isRunning) break;

        if (swapchain == VK_NULL_HANDLE || cmdBufs.empty()) continue;
        try { renderFrame(); } catch(...) {}
    }
}

void VulkanRendererContext::flushDeleteQueue() {
    if (deleteQueue.empty()) return;
    if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, inFlightFences[currentFrame]) == VK_NOT_READY) {
        vk_.WaitForFences(device,1,&inFlightFences[currentFrame],VK_TRUE,UINT64_MAX);
    }
    for (auto& wt:deleteQueue) {
        if (!wt.isAHB) {
            if (wt.ds!=VK_NULL_HANDLE) vk_.FreeDescriptorSets(device,winTexPool,1,&wt.ds);
            if (wt.view!=VK_NULL_HANDLE) vk_.DestroyImageView(device,wt.view,nullptr);
            if (wt.img!=VK_NULL_HANDLE) vk_.DestroyImage(device,wt.img,nullptr);
            if (wt.mem!=VK_NULL_HANDLE) vk_.FreeMemory(device,wt.mem,nullptr);
        }
        if (wt.stg!=VK_NULL_HANDLE){vk_.DestroyBuffer(device,wt.stg,nullptr);vk_.FreeMemory(device,wt.stgMem,nullptr);}
    }
    deleteQueue.clear();
}

void VulkanRendererContext::renderFrame() {
    std::shared_lock<std::shared_mutex> frameLock(frameMutex);

    needsRender.store(false,std::memory_order_relaxed);
    cursorMoved.store(false,std::memory_order_relaxed);
    if (surfaceDetached.load(std::memory_order_acquire)) return;
    if (scanoutActive.load()) {
        applyScanoutBuffer();

        if (!scanoutBlackFrameDone.load()) {
            scanoutBlackFrameDone.store(true);

            std::lock_guard<std::mutex> lk(renderMutex);
            renderList.clear();
        } else {
            return;
        }
    } else {
        scanoutBlackFrameDone.store(false);
    }
    if (surfaceWidth==0||surfaceHeight==0) return;

    if (fbResized.load()) {
        for (auto& f:inFlightFences) vk_.WaitForFences(device,1,&f,VK_TRUE,UINT64_MAX);
        cleanupSwapchain();
        bool ok=false;
        try{createSwapchain();createFramebuffers();createCmdBufs();imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
ok=true;}catch(...){}
        if (ok) fbResized.store(false);
        return;
    }

    flushDeleteQueue();
    if (currentFrame >= cmdBufs.size() || cmdBufs[currentFrame] == VK_NULL_HANDLE) return;
    bool currentFenceWaited = false;
    if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, inFlightFences[currentFrame]) == VK_NOT_READY) {
        vk_.WaitForFences(device,1,&inFlightFences[currentFrame],VK_TRUE,UINT64_MAX);
        currentFenceWaited = true;
    }

    uint32_t imgIdx;
    VkResult res=vk_.AcquireNextImageKHR(device,swapchain,UINT64_MAX,imgAvailSems[currentFrame],VK_NULL_HANDLE,&imgIdx);
    if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR){fbResized.store(true);return;}
    if (res!=VK_SUCCESS&&res!=VK_SUBOPTIMAL_KHR) return;
    if (imgIdx >= swapchainFBs.size() || imgIdx >= swapchainImages.size()) {
        RLOG_E("renderFrame: invalid acquired image index=%u (fb=%zu images=%zu)",
            imgIdx, swapchainFBs.size(), swapchainImages.size());
        return;
    }

    if (imgInFlight.size()!=swapchainImages.size()) imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
    if (imgInFlight[imgIdx]!=VK_NULL_HANDLE &&
        (!currentFenceWaited || imgInFlight[imgIdx] != inFlightFences[currentFrame])) {
        if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, imgInFlight[imgIdx]) == VK_NOT_READY) {
            vk_.WaitForFences(device,1,&imgInFlight[imgIdx],VK_TRUE,UINT64_MAX);
        }
    }
    imgInFlight[imgIdx]=inFlightFences[currentFrame];

    vk_.ResetCommandBuffer(cmdBufs[currentFrame],0);

    std::vector<DrawEntry> draws;
    draws.reserve(renderList.size());
    float ox,oy,sx,sy,cw,ch;
    short ptrX,ptrY,curHotX,curHotY,curW,curH; bool curVis;
    VkBuffer curUpload=VK_NULL_HANDLE; bool hasCurUpload=false;

    {
        std::lock_guard<std::mutex> lk(renderMutex);
        ox=sceneOffsetX; oy=sceneOffsetY; sx=sceneScaleX; sy=sceneScaleY;
        cw=(float)containerWidth; ch=(float)containerHeight;
        ptrX=(short)pointerX.load(); ptrY=(short)pointerY.load();
        curHotX=cursorHotX; curHotY=cursorHotY; curW=cursorTexW; curH=cursorTexH;
        curVis=cursorVisible.load();

        for (auto& re:renderList) {
            auto it=texMap.find(re.id);
            if (it==texMap.end()) continue;
            WinTex& wt=it->second;
            if (wt.ds==VK_NULL_HANDLE) continue;
            DrawEntry de{wt.img,wt.ds,VK_NULL_HANDLE,re.x,re.y,wt.w,wt.h};
            de.useLinear=wt.useLinear;
            de.isAHB=wt.isAHB;
            if (wt.needsTransition) { de.needsTransition=true; wt.needsTransition=false; }
            if (wt.dirty && !wt.isAHB) {
                if (wt.useLinear) de.dirtyLinear=true;
                else if (wt.stg!=VK_NULL_HANDLE) de.upload=wt.stg;
                wt.dirty=false;
            } else if (wt.isAHB && wt.dirty) {
                de.dirtyAHB = true;
                wt.dirty=false;
            }
            draws.push_back(de);
        }

        if (isCursorImageDirty.load() && cursorImg!=VK_NULL_HANDLE && !cursorPixels.empty()) {
            VkDeviceSize csz=(VkDeviceSize)cursorTexW*cursorTexH*4;
            ensureCursorStaging(csz);
            memcpy(cursorStgP,cursorPixels.data(),csz);
            isCursorImageDirty.store(false); hasCurUpload=true; curUpload=cursorStg;
        }
    }

    bool effectiveCurVis = curVis && !scanoutActive.load();
    recordCmdBuf(cmdBufs[currentFrame],imgIdx,draws,curUpload,hasCurUpload,
        ox,oy,sx,sy,cw,ch,ptrX,ptrY,curHotX,curHotY,curW,curH,effectiveCurVis);

    VkSemaphore wSem[]={imgAvailSems[currentFrame]}, sSem[]={renderDoneSems[currentFrame]};
    VkPipelineStageFlags wStage[]={VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount=1; si.pWaitSemaphores=wSem; si.pWaitDstStageMask=wStage;
    si.commandBufferCount=1; si.pCommandBuffers=&cmdBufs[currentFrame];
    si.signalSemaphoreCount=1; si.pSignalSemaphores=sSem;

    vk_.ResetFences(device,1,&inFlightFences[currentFrame]);
    if (vk_.QueueSubmit(graphicsQueue,1,&si,inFlightFences[currentFrame])!=VK_SUCCESS) {
        vk_.DestroyFence(device,inFlightFences[currentFrame],nullptr);
        VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
        vk_.CreateFence(device,&fi,nullptr,&inFlightFences[currentFrame]);
        return;
    }
    VkSwapchainKHR scs[]={swapchain};
    VkPresentInfoKHR pi{}; pi.sType=VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.waitSemaphoreCount=1; pi.pWaitSemaphores=sSem; pi.swapchainCount=1; pi.pSwapchains=scs; pi.pImageIndices=&imgIdx;

    /* === NATIVE X11 LATENCY: VK_GOOGLE_display_timing setup ===
     *
     * If the extension is supported, attach a unique presentId to this
     * present and record (presentId, arriveT1) in the ring buffer. After
     * QueuePresent returns we'll query past timings — the driver tells
     * us the CLOCK_MONOTONIC nanosecond at which each past presentId was
     * actually displayed on the panel. That's an apples-to-apples T2 vs
     * DAC's setOnCommit (which fires at SF apply-tick, i.e. "buffer is
     * on the display").
     *
     * desiredPresentTime=0 means "display ASAP" — no pacing constraint,
     * just give us back the actual time.
     *
     * Without the extension we fall back to the previous QueuePresent-
     * return timing below — directionally useful but biased low by ~1 vsync. */
    VkPresentTimeGOOGLE pt{};
    VkPresentTimesInfoGOOGLE pti{};
    uint64_t thisArriveT1 = latencyX11ArriveUs.exchange(0, std::memory_order_relaxed);
    if (displayTimingSupported && thisArriveT1 != 0) {
        uint64_t thisPresentId = nextPresentId++;
        pt.presentID = thisPresentId;
        pt.desiredPresentTime = 0;
        pti.sType = VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE;
        pti.pNext = nullptr;
        pti.swapchainCount = 1;
        pti.pTimes = &pt;
        pi.pNext = &pti;
        presentRing[presentRingHead] = { thisPresentId, thisArriveT1 };
        presentRingHead = (presentRingHead + 1) % PRESENT_RING_SIZE;
    }

    res=vk_.QueuePresentKHR(graphicsQueue,&pi);
    if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR) fbResized.store(true);

    /* === T2 path A: query VK_GOOGLE_display_timing past timings ===
     *
     * vkGetPastPresentationTimingGOOGLE is non-blocking — returns whatever
     * timings the driver has finalized (typically the present from ~2 frames
     * ago). For each returned (presentId, actualPresentTime), look up the
     * matching ring entry, compute T2 - T1, feed the EMA. */
    if (displayTimingSupported && vk_.GetPastPresentationTimingGOOGLE
        && res >= 0) {
        uint32_t timingCount = 0;
        vk_.GetPastPresentationTimingGOOGLE(device, swapchain, &timingCount, nullptr);
        if (timingCount > 0) {
            if (timingCount > PRESENT_RING_SIZE) timingCount = PRESENT_RING_SIZE;
            VkPastPresentationTimingGOOGLE timings[PRESENT_RING_SIZE];
            vk_.GetPastPresentationTimingGOOGLE(device, swapchain, &timingCount, timings);
            for (uint32_t i = 0; i < timingCount; i++) {
                /* actualPresentTime is CLOCK_MONOTONIC nanoseconds.
                 * Convert to µs to match our EMA resolution. */
                uint64_t displayedUs = timings[i].actualPresentTime / 1000ULL;
                uint64_t pid = timings[i].presentID;
                for (int j = 0; j < PRESENT_RING_SIZE; j++) {
                    if (presentRing[j].presentId == pid
                        && presentRing[j].arriveT1Us != 0) {
                        if (displayedUs > presentRing[j].arriveT1Us) {
                            uint64_t delta = displayedUs - presentRing[j].arriveT1Us;
                            if (delta < 500000ULL) {  /* < 500 ms outlier guard */
                                uint64_t prev = latencyEmaUs.load(std::memory_order_relaxed);
                                uint64_t next = (prev == 0) ? delta : (prev * 7 + delta) / 8;
                                latencyEmaUs.store(next, std::memory_order_relaxed);
                            }
                        }
                        /* Mark consumed so a repeated echo doesn't double-count. */
                        presentRing[j].arriveT1Us = 0;
                        break;
                    }
                }
            }
        }
    }
    /* === T2 path B (fallback): QueuePresent-return ===
     *
     * Used on devices without VK_GOOGLE_display_timing. Same code as Phase 2:
     * captures T2 immediately after QueuePresent returns. Under-reports the
     * true compositor latency by ~1 vsync (SF queue + apply not included),
     * but moves with the underlying compositor cost. */
    else if (!displayTimingSupported && res >= 0 && thisArriveT1 != 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        uint64_t now_us = (uint64_t)ts.tv_sec * 1000000ULL
                        + (uint64_t)ts.tv_nsec / 1000ULL;
        if (now_us > thisArriveT1) {
            uint64_t delta = now_us - thisArriveT1;
            if (delta < 500000ULL) {
                uint64_t prev = latencyEmaUs.load(std::memory_order_relaxed);
                uint64_t next = (prev == 0) ? delta : (prev * 7 + delta) / 8;
                latencyEmaUs.store(next, std::memory_order_relaxed);
            }
        }
    }

    currentFrame=(currentFrame+1)%MAX_FRAMES_IN_FLIGHT;
}

void VulkanRendererContext::onSurfaceResized(int w, int h) {
    std::lock_guard<std::mutex> lk(renderMutex);
    if (w==0||h==0) return;
    surfaceWidth=w; surfaceHeight=h; fbResized.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::detachSurface() {
    surfaceDetached.store(true, std::memory_order_release);
    dirtyCV.notify_all();

    { std::unique_lock<std::shared_mutex> frameLock(frameMutex); }

    vk_.DeviceWaitIdle(device);
    cleanupSwapchain();
    if (surface != VK_NULL_HANDLE) {
        vk_.DestroySurfaceKHR(instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
    }
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
}

bool VulkanRendererContext::reattachSurface(ANativeWindow* newWindow) {
    if (window) { ANativeWindow_release(window); window = nullptr; }
    window = newWindow;
    VkAndroidSurfaceCreateInfoKHR ci{};
    ci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window = window;
    if (vk_.CreateAndroidSurfaceKHR(instance, &ci, nullptr, &surface) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: CreateAndroidSurface failed");
        ANativeWindow_release(window); window = nullptr;
        return false;
    }
    {
        std::unique_lock<std::shared_mutex> frameLock(frameMutex);
        try {
            createSwapchain();
            createFramebuffers();
            createCmdBufs();
            imgInFlight.assign(swapchainImages.size(), VK_NULL_HANDLE);
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: swapchain recreate failed");
            return false;
        }
        surfaceDetached.store(false, std::memory_order_release);
    }
    needsRender.store(true, std::memory_order_release);
    dirtyCV.notify_all();
    __android_log_print(ANDROID_LOG_DEBUG, "Winlator_Renderer", "reattachSurface: OK");
    return true;
}

void VulkanRendererContext::setTransform(float ox, float oy, float sx, float sy) {
    { std::lock_guard<std::mutex> lk(renderMutex); sceneOffsetX=ox;sceneOffsetY=oy;sceneScaleX=sx;sceneScaleY=sy; }
    needsRender.store(true); dirtyCV.notify_one();
}
void VulkanRendererContext::updatePointerPosition(short x, short y) {
    pointerX.store(x); pointerY.store(y);
    if (cursorVisible.load()) { cursorMoved.store(true); dirtyCV.notify_one(); }
}
void VulkanRendererContext::setCursorVisible(bool v) {
    cursorVisible.store(v); cursorMoved.store(true); dirtyCV.notify_one();
}
void VulkanRendererContext::updateCursorImage(void* px, short w, short h, short hotX, short hotY) {
    if (!px||w<=0||h<=0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    ensureCursorTex(w,h);
    cursorPixels.resize((size_t)w*h); memcpy(cursorPixels.data(),px,(size_t)w*h*4);
    cursorHotX=hotX; cursorHotY=hotY;
    isCursorImageDirty.store(true); needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updateWindowContent(int64_t id, void* px, short w, short h, short stride, int, int) {
    if (!px||w<=0||h<=0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    WinTex& wt=texMap[id];
    if (wt.img==VK_NULL_HANDLE || wt.w!=w || wt.h!=h) {
        if (wt.img!=VK_NULL_HANDLE) destroyWinTex(wt);
        if (!createWinTexResources(wt,w,h)) { texMap.erase(id); return; }
    }
    if (!wt.mapped) return;

    size_t dstPitch = wt.useLinear ? (size_t)wt.imgLayout.rowPitch : (size_t)w * 4;
    wt.dirty = true;

    const int32_t srcStride = stride > 0 ? stride : w;
    uint32_t* src2 = static_cast<uint32_t*>(px);
    uint8_t*  dst2 = static_cast<uint8_t*>(wt.mapped);
    const bool stridesMatch = (srcStride == w) &&
                              (dstPitch == (size_t)w * 4);
    if (stridesMatch) {
        memcpy(dst2, px, (size_t)w * h * 4);
    } else {
        for (int row = 0; row < h; ++row) {
            memcpy(dst2 + (size_t)row * dstPitch,
                   src2 + (size_t)row * srcStride,
                   (size_t)w * 4);
        }
    }
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short, short, int, int) {
    if (!ahb) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    WinTex& wt=texMap[id];

    if (wt.ahb==ahb) { needsRender.store(true); dirtyCV.notify_one(); return; }

    auto cit=ahbTexCache.find(ahb);
    if (cit!=ahbTexCache.end()) {
        wt.img=cit->second.img; wt.mem=cit->second.mem;
        wt.view=cit->second.view; wt.ds=cit->second.ds;
        wt.isAHB=true; wt.ahb=ahb;
        AHardwareBuffer_Desc d{}; AHardwareBuffer_describe(ahb,&d);
        wt.w=(int)d.width; wt.h=(int)d.height;
        needsRender.store(true); dirtyCV.notify_one();
        return;
    }

    if (wt.img!=VK_NULL_HANDLE && !wt.isAHB) {
        destroyWinTex(wt);
    } else {
        wt={};
    }

    WinTex tmp{};
    if (!importAHBToWinTex(tmp,ahb)) { texMap.erase(id); return; }

    AHBCached cached{tmp.img,tmp.mem,tmp.view,tmp.ds};
    AHardwareBuffer_acquire(ahb);
    ahbTexCache[ahb]=cached;
    ahbCacheLRU.push_back(ahb);

    size_t cacheMax = scanoutActive.load() ? AHB_CACHE_MAX_SCANOUT : AHB_CACHE_MAX_NORMAL;
    while (ahbTexCache.size() > cacheMax) {
        AHardwareBuffer* oldest = ahbCacheLRU.front();
        ahbCacheLRU.pop_front();
        auto eit = ahbTexCache.find(oldest);
        if (eit != ahbTexCache.end()) {
            WinTex deferred{};
            deferred.img  = eit->second.img;
            deferred.mem  = eit->second.mem;
            deferred.view = eit->second.view;
            deferred.ds   = eit->second.ds;
            deferred.isAHB = false;
            deleteQueue.push_back(deferred);
            AHardwareBuffer_release(oldest);
            ahbTexCache.erase(eit);
        }
    }

    wt.img=cached.img; wt.mem=cached.mem; wt.view=cached.view; wt.ds=cached.ds;
    wt.isAHB=true; wt.ahb=ahb;
    AHardwareBuffer_Desc d{}; AHardwareBuffer_describe(ahb,&d);
    wt.w=(int)d.width; wt.h=(int)d.height;
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::setRenderList(const int64_t* ids, const int* xs, const int* ys, int count) {
    std::lock_guard<std::mutex> lk(renderMutex);
    renderList.resize(count);
    for (int i=0;i<count;i++) renderList[i]={ids[i],xs[i],ys[i]};
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::removeWindow(int64_t id) {
    std::lock_guard<std::mutex> lk(renderMutex);
    auto it=texMap.find(id);
    if (it!=texMap.end()){destroyWinTex(it->second);texMap.erase(it);}
    renderList.erase(std::remove_if(renderList.begin(),renderList.end(),[id](const RenderEntry& e){return e.id==id;}),renderList.end());
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::cleanupAllAHBCache() {
    for (auto& [ahb,c]:ahbTexCache) {
        if (c.ds!=VK_NULL_HANDLE) vk_.FreeDescriptorSets(device,winTexPool,1,&c.ds);
        if (c.view!=VK_NULL_HANDLE) vk_.DestroyImageView(device,c.view,nullptr);
        if (c.img!=VK_NULL_HANDLE) vk_.DestroyImage(device,c.img,nullptr);
        if (c.mem!=VK_NULL_HANDLE) vk_.FreeMemory(device,c.mem,nullptr);
        AHardwareBuffer_release(ahb);
    }
    ahbTexCache.clear();
    ahbCacheLRU.clear();
}

#include <dlfcn.h>
#include <android/api-level.h>
#include <android/log.h>
#define SCANOUT_LOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Scanout",__VA_ARGS__)

typedef void* (*pfn_SCCreateFromWindow)(ANativeWindow*, const char*);
typedef void  (*pfn_SCRelease)(void*);
typedef void* (*pfn_STCreate)();
typedef void  (*pfn_STDelete)(void*);
typedef void  (*pfn_STApply)(void*);
typedef void  (*pfn_STSetBuffer)(void*, void*, AHardwareBuffer*, int);
typedef void  (*pfn_STSetZOrder)(void*, void*, int32_t);
typedef void  (*pfn_STSetVisibility)(void*, void*, int8_t);
typedef void  (*pfn_STSetGeometry)(void*, void*, const ARect*, const ARect*, int32_t);
typedef void  (*pfn_STSetBackPressure)(void*, void*, bool);
typedef void  (*pfn_STSetOnComplete)(void*, void*, void (*)(void*, void*));

bool VulkanRendererContext::loadScanoutApi() {
    if (scanoutApiLoaded) return fnSCCreateFromWin != nullptr;
    scanoutApiLoaded = true;
    int apiLevel = android_get_device_api_level();
    if (apiLevel < 29) { SCANOUT_LOG("loadScanoutApi: API < 29, unavailable"); return false; }

    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) { SCANOUT_LOG("loadScanoutApi: dlopen libandroid.so failed: %s", dlerror()); return false; }

    fnSCCreateFromWin = dlsym(lib, "ASurfaceControl_createFromWindow");
    fnSCRelease       = dlsym(lib, "ASurfaceControl_release");
    fnSTCreate        = dlsym(lib, "ASurfaceTransaction_create");
    fnSTDelete        = dlsym(lib, "ASurfaceTransaction_delete");
    fnSTApply         = dlsym(lib, "ASurfaceTransaction_apply");
    fnSTSetBuffer     = dlsym(lib, "ASurfaceTransaction_setBuffer");
    fnSTSetZOrder     = dlsym(lib, "ASurfaceTransaction_setZOrder");
    fnSTSetVisibility = dlsym(lib, "ASurfaceTransaction_setVisibility");
    fnSTSetGeometry      = dlsym(lib, "ASurfaceTransaction_setGeometry");
    fnSTSetBackPressure  = dlsym(lib, "ASurfaceTransaction_setEnableBackPressure");
    fnSTSetOnComplete    = dlsym(lib, "ASurfaceTransaction_setOnComplete");
    fnSTSetOnCommit      = dlsym(lib, "ASurfaceTransaction_setOnCommit");   /* API 31+; preferred over setOnComplete for vsync pacing */
    fnSTSetFrameRate     = dlsym(lib, "ASurfaceTransaction_setFrameRate"); /* API 30+; may be NULL on older Android */
    fnSTSetBufferTransparency = dlsym(lib, "ASurfaceTransaction_setBufferTransparency"); /* API 29+ — mark SC opaque so SurfaceFlinger doesn't alpha-blend against background */

    bool coreOk = fnSCCreateFromWin && fnSCRelease &&
                  fnSTCreate && fnSTDelete && fnSTApply &&
                  fnSTSetBuffer && fnSTSetVisibility && fnSTSetGeometry;
    if (!coreOk) {
        SCANOUT_LOG("loadScanoutApi: core symbols missing, scanout disabled");
        fnSCCreateFromWin = fnSCRelease = fnSTCreate = fnSTDelete = fnSTApply =
        fnSTSetBuffer = fnSTSetZOrder = fnSTSetVisibility = fnSTSetGeometry = nullptr;
        return false;
    }
    if (!fnSTSetZOrder) SCANOUT_LOG("loadScanoutApi: setZOrder unavailable (non-critical)");
    SCANOUT_LOG("loadScanoutApi: setFrameRate %s (needed for >60Hz refresh)",
        fnSTSetFrameRate ? "available" : "NOT AVAILABLE (Android < 11?)");
    SCANOUT_LOG("loadScanoutApi: setOnCommit %s (drives DXVK vkWaitForPresentKHR pacing)",
        fnSTSetOnCommit ? "available" : "NOT AVAILABLE (Android < 12; falling back to setOnComplete)");
    const char* gpuBlitEnv = std::getenv("WINLATOR_SCANOUT_GPU_BLIT");
    scanoutEnvGpuBlit = (!gpuBlitEnv || gpuBlitEnv[0] == '1');
    scanoutAlwaysGpuBlit = scanoutEnvGpuBlit || swapRB;
    SCANOUT_LOG("loadScanoutApi: envGpuBlit=%d swapRB=%d alwaysGpuBlit=%d",
        (int)scanoutEnvGpuBlit, (int)swapRB, (int)scanoutAlwaysGpuBlit);
    SCANOUT_LOG("loadScanoutApi: core symbols OK, scanout enabled");
    return true;
}

#define SC_CREATE(win, name)     ((pfn_SCCreateFromWindow)fnSCCreateFromWin)((win), (name))
#define SC_RELEASE(sc)        ((pfn_SCRelease)fnSCRelease)((sc))
#define ST_CREATE()           ((pfn_STCreate)fnSTCreate)()
#define ST_DELETE(t)          ((pfn_STDelete)fnSTDelete)((t))
#define ST_APPLY(t)           ((pfn_STApply)fnSTApply)((t))
#define ST_SETBUF(t,sc,b,f)   ((pfn_STSetBuffer)fnSTSetBuffer)((t),(sc),(b),(f))
#define ST_SETZORDER(t,sc,z)  if(fnSTSetZOrder) ((pfn_STSetZOrder)fnSTSetZOrder)((t),(sc),(z))
#define ST_SETVIS(t,sc,v)     ((pfn_STSetVisibility)fnSTSetVisibility)((t),(sc),(v))
#define ST_SETGEO(t,sc,s,d,r)  ((pfn_STSetGeometry)fnSTSetGeometry)((t),(sc),(s),(d),(r))
#define ST_SETBP(t,sc,v)       if(fnSTSetBackPressure) ((pfn_STSetBackPressure)fnSTSetBackPressure)((t),(sc),(v))
/* setBufferTransparency: API 29+. transparency=2 (OPAQUE) tells SurfaceFlinger to
 * ignore the buffer's alpha channel during composition. DXVK leaves garbage in
 * the alpha channel of its render targets (PC monitors aren't transparent so it
 * doesn't bother). Without this flag, SurfaceFlinger treats those garbage alpha
 * values as semi-transparency hints and alpha-blends our live frame against
 * whatever stale framebuffer sits underneath the SurfaceControl — producing
 * "ghosted/doubled motion" during camera movement because the eye sees both
 * the new frame and a faded copy of an older one. Marking the SC OPAQUE makes
 * compositing a straight overwrite and eliminates the ghost. */
#define ST_TRANSPARENCY_OPAQUE 2
#define ST_SETOPAQUE(t,sc)     do { if (fnSTSetBufferTransparency) \
    ((void(*)(void*,void*,int8_t))fnSTSetBufferTransparency)((t),(sc),ST_TRANSPARENCY_OPAQUE); } while(0)

void VulkanRendererContext::initScanout() {
    if (scanoutActive.load()) return;
    if (!window || !loadScanoutApi()) {
        SCANOUT_LOG("initScanout: loadApi failed, aborting");
        return;
    }

    scanoutGameSC   = SC_CREATE(window, "winlator_game");
    scanoutCursorSC = SC_CREATE(window, "winlator_cursor");
    if (!scanoutGameSC || !scanoutCursorSC) {
        SCANOUT_LOG("initScanout: SC creation failed, aborting");
        if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
        if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }
        return;
    }

    void* t = ST_CREATE();
    ST_SETZORDER(t, scanoutGameSC, 0);
    ST_SETZORDER(t, scanoutCursorSC, 1);
    ST_SETVIS(t, scanoutGameSC,   0);
    ST_SETVIS(t, scanoutCursorSC, 0);

    /* NOTE: previously called ST_SETOPAQUE(t, scanoutGameSC) here based on
     * the hypothesis that DXVK's garbage alpha was causing SurfaceFlinger
     * to alpha-blend the live frame against the stale background. On the
     * Odin 2 Portal that change appears to nudge SurfaceFlinger onto the
     * hardware-overlay composition path, which on this SoC doesn't honor
     * the layer's exported acquire-fence — producing tearing and a slot
     * release stall that pinned FPS at ~15. Leaving the SC in default
     * TRANSLUCENT mode until we have a verified-working hardware-overlay
     * acquire-fence path. */

    /* Request the panel's max refresh rate (e.g., 120 Hz on Odin 2 Portal).
     * Compatibility=0 (DEFAULT) tells SurfaceFlinger to pick the nearest rate
     * the panel actually supports. Without this, SurfaceFlinger defaults to
     * 60 Hz which caps our onComplete-paced release chain at 60 fps. */
    if (fnSTSetFrameRate) {
        typedef void (*pfn_STSetFrameRate)(void*, void*, float, int8_t);
        ((pfn_STSetFrameRate)fnSTSetFrameRate)(t, scanoutGameSC,   120.0f, 0);
        ((pfn_STSetFrameRate)fnSTSetFrameRate)(t, scanoutCursorSC, 120.0f, 0);
        SCANOUT_LOG("initScanout: requested 120 Hz frame rate on game/cursor SC");
    }

    ST_APPLY(t);
    ST_DELETE(t);

    gameScVisible = false; lastDstW = 0;
    gameFrameDelivered.store(false);
    scanoutActive.store(true);
    if (scanoutBlitCb == VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo cbi{};
        cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cbi.commandPool = cmdPool;
        cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbi.commandBufferCount = 1;
        if (vk_.AllocateCommandBuffers(device, &cbi, &scanoutBlitCb) != VK_SUCCESS) {
            scanoutBlitCb = VK_NULL_HANDLE;
        }
    }
    if (scanoutBlitFence == VK_NULL_HANDLE) {
        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vk_.CreateFence(device, &fi, nullptr, &scanoutBlitFence) != VK_SUCCESS) {
            scanoutBlitFence = VK_NULL_HANDLE;
        }
    }
    scanoutAlwaysGpuBlit = scanoutEnvGpuBlit || swapRB;
    scanoutNeedsGpuBlit = scanoutAlwaysGpuBlit;
    SCANOUT_LOG("initScanout: SUCCESS, scanout active");
}

void VulkanRendererContext::destroyScanout() {
    if (!scanoutActive.load()) return;
    scanoutActive.store(false);

    if (scanoutGameSC || scanoutCursorSC) {
        void* t = ST_CREATE();
        if (scanoutGameSC)   ST_SETVIS(t, scanoutGameSC,   0);
        if (scanoutCursorSC) ST_SETVIS(t, scanoutCursorSC, 0);
        ST_APPLY(t);
        ST_DELETE(t);
    }

    if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
    if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }
    if (scanoutCursorBuf) {
        AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer*>(scanoutCursorBuf));
        scanoutCursorBuf=nullptr;
    }
    if (scanoutLocalImg != VK_NULL_HANDLE) {
        vk_.DestroyImage(device, scanoutLocalImg, nullptr);
        scanoutLocalImg = VK_NULL_HANDLE;
    }
    if (scanoutLocalMem != VK_NULL_HANDLE) {
        vk_.FreeMemory(device, scanoutLocalMem, nullptr);
        scanoutLocalMem = VK_NULL_HANDLE;
    }
    if (scanoutLocalAhb) {
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
    }
    if (scanoutBlitFence != VK_NULL_HANDLE) {
        vk_.WaitForFences(device, 1, &scanoutBlitFence, VK_TRUE, UINT64_MAX);
        vk_.DestroyFence(device, scanoutBlitFence, nullptr);
        scanoutBlitFence = VK_NULL_HANDLE;
    }
    if (scanoutBlitCb != VK_NULL_HANDLE) {
        vk_.FreeCommandBuffers(device, cmdPool, 1, &scanoutBlitCb);
        scanoutBlitCb = VK_NULL_HANDLE;
    }
    scanoutLocalW = scanoutLocalH = 0;
    scanoutNeedsGpuBlit = false;
    scanoutCursorBufW = scanoutCursorBufH = 0;
}

void VulkanRendererContext::scanoutSetBuffer(AHardwareBuffer* ahb, int acquireFenceFd, int slotIndex, int x, int y, int w, int h, int bgraBytes) {
    if (!scanoutActive.load() || !scanoutGameSC || !ahb) {
        RLOG("scanoutSetBuffer: SKIPPED active=%d sc=%p ahb=%p slot=%d",
            (int)scanoutActive.load(),scanoutGameSC,(void*)ahb,slotIndex);
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        /* Send MSG_RELEASE for this slot so Wine's AHB pool doesn't count
         * it as in-flight. Without this, even brief scanoutActive=false
         * windows (lock/unlock, transient onPause/onResume cycles from
         * Android lifecycle events) leak slots — after 4 consecutive
         * skipped frames, Wine's vkAcquireNextImageKHR blocks on
         * pthread_cond_wait forever waiting for releases that will never
         * come, and the game freezes. The release is marked displayed=0
         * so the layer's pacing logic doesn't tick its display counter
         * for a frame that was never actually displayed.
         *
         * Note: there's a similar fix in applyScanoutBuffer's SKIPPED
         * path for the case where the SC state changes between this
         * function and apply — in practice the early-return here catches
         * essentially all such events because scanoutGameSC is the first
         * thing nulled when DirectCompositorComponent.onPause runs. */
        if (slotIndex >= 0) {
            int sockFd = scanoutSocketFd.load(std::memory_order_relaxed);
            if (sockFd >= 0) {
                struct { uint8_t type; uint32_t slot_index; int32_t release_fd;
                         uint8_t displayed; uint64_t vsync_time_ns; } rel{};
                rel.type = 2; // MSG_RELEASE
                rel.slot_index = (uint32_t)slotIndex;
                rel.release_fd = -1;
                rel.displayed = 0;
                send(sockFd, &rel, sizeof(rel), MSG_NOSIGNAL);
            }
        }
        return;
    }
    static int _scanoutBufCnt=0;
    if (++_scanoutBufCnt == 1) {
        AHardwareBuffer_Desc d{};
        AHardwareBuffer_describe(ahb, &d);
        bool hasOverlay = (d.usage & AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY) != 0;
        RLOG("FIRST FRAME: fmt=%u %ux%u usage=0x%llx COMPOSER_OVERLAY=%s",
            d.format, d.width, d.height, (unsigned long long)d.usage,
            hasOverlay ? "YES" : "NO");
        scanoutNeedsGpuBlit = !hasOverlay;
        /* NOTE: We previously tried to skip the local blit when the AHB had
         * COMPOSER_OVERLAY. That regressed on the Odin 2 Portal: SurfaceFlinger
         * put the SurfaceControl on a hardware overlay but didn't honor the
         * layer's exported acquire-fence, producing visible scanline tearing,
         * and slot release switched from ~1ms (local-copy-then-free) to
         * ~33ms (OnComplete-driven), starving the layer's slot pool and
         * collapsing FPS to ~15 via the pacing budget. Kept the local-blit
         * path active by default until we have a working acquire-fence path
         * on hardware overlays for this SoC. */
    }
    // AHardwareBuffer_acquire removed: pool owns the ref, SurfaceFlinger manages its own
    { std::lock_guard<std::mutex> lk(scanoutMutex);
      if (scanoutPendingDirty.load() && scanoutPending.acquireFenceFd >= 0) {
          close(scanoutPending.acquireFenceFd);
      }
      scanoutPending = {ahb, acquireFenceFd, slotIndex, x, y, w, h, bgraBytes};
      scanoutPendingDirty.store(true, std::memory_order_release); }
    needsRender.store(true, std::memory_order_release);
    dirtyCV.notify_one();
}

void VulkanRendererContext::initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin) {
    if (scanoutActive.load()) destroyScanout();
    if (!loadScanoutApi()) {
        ANativeWindow_release(gameWin); ANativeWindow_release(cursorWin);
        initScanout(); return;
    }
    scanoutGameSC   = SC_CREATE(gameWin,   "winlator_game_buf");
    scanoutCursorSC = SC_CREATE(cursorWin, "winlator_cursor_buf");
    ANativeWindow_release(gameWin); ANativeWindow_release(cursorWin);
    if (!scanoutGameSC || !scanoutCursorSC) {
        SCANOUT_LOG("initScanoutFromWindows: SC creation failed, fallback to child path");
        if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
        if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }
        initScanout(); return;
    }
    void* t = ST_CREATE();
    ST_SETZORDER(t, scanoutGameSC, 0);
    ST_SETZORDER(t, scanoutCursorSC, 1);
    ST_SETVIS(t, scanoutGameSC,   0);
    ST_SETVIS(t, scanoutCursorSC, 0);
    /* Game SC kept in default TRANSLUCENT mode — see initScanout() for why
     * we don't call ST_SETOPAQUE on this SoC. */
    ST_APPLY(t); ST_DELETE(t);
    gameScVisible = false; lastDstW = 0;
    gameFrameDelivered.store(false);
    scanoutActive.store(true);
    if (scanoutBlitCb == VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo cbi{};
        cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cbi.commandPool = cmdPool;
        cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbi.commandBufferCount = 1;
        if (vk_.AllocateCommandBuffers(device, &cbi, &scanoutBlitCb) != VK_SUCCESS) {
            scanoutBlitCb = VK_NULL_HANDLE;
        }
    }
    if (scanoutBlitFence == VK_NULL_HANDLE) {
        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vk_.CreateFence(device, &fi, nullptr, &scanoutBlitFence) != VK_SUCCESS) {
            scanoutBlitFence = VK_NULL_HANDLE;
        }
    }
    scanoutAlwaysGpuBlit = scanoutEnvGpuBlit || swapRB;
    scanoutNeedsGpuBlit = scanoutAlwaysGpuBlit;
    SCANOUT_LOG("initScanoutFromWindows: SUCCESS (sibling path via Java getSurfaceControl)");
}

bool VulkanRendererContext::ensureScanoutLocalAhb(int w, int h, uint32_t ahbFormat) {
    if (scanoutLocalAhb && scanoutLocalImg != VK_NULL_HANDLE &&
        scanoutLocalW == w && scanoutLocalH == h &&
        scanoutLocalAhbFormat == ahbFormat) {
        return true;
    }

    if (scanoutLocalImg != VK_NULL_HANDLE) {
        vk_.DeviceWaitIdle(device);
        vk_.DestroyImage(device, scanoutLocalImg, nullptr);
        scanoutLocalImg = VK_NULL_HANDLE;
    }
    if (scanoutLocalMem != VK_NULL_HANDLE) {
        vk_.FreeMemory(device, scanoutLocalMem, nullptr);
        scanoutLocalMem = VK_NULL_HANDLE;
    }
    if (scanoutLocalAhb) {
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
    }

    AHardwareBuffer_Desc d{};
    d.width = (uint32_t)w;
    d.height = (uint32_t)h;
    d.layers = 1;
    d.format = ahbFormat;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER
            | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
            | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    if (AHardwareBuffer_allocate(&d, &scanoutLocalAhb) != 0) return false;

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtP{};
    fmtP.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext = &fmtP;
    if (vk_.GetAndroidHardwareBufferPropertiesANDROID(device, scanoutLocalAhb, &props) != VK_SUCCESS) {
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
        return false;
    }

    bool extFmt = (fmtP.format == VK_FORMAT_UNDEFINED);
    VkExternalFormatANDROID ef{};
    ef.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    ef.externalFormat = extFmt ? fmtP.externalFormat : 0;
    VkExternalMemoryImageCreateInfo emi{};
    emi.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emi.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    if (extFmt) {
        ef.pNext = const_cast<void*>(emi.pNext);
        emi.pNext = &ef;
    }

    VkImageCreateInfo ii{};
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.pNext = &emi;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = extFmt ? VK_FORMAT_UNDEFINED : fmtP.format;
    ii.extent = {(uint32_t)w, (uint32_t)h, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device, &ii, nullptr, &scanoutLocalImg) != VK_SUCCESS) {
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID imp{};
    imp.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    imp.buffer = scanoutLocalAhb;
    VkMemoryDedicatedAllocateInfo ded{};
    ded.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    ded.pNext = &imp;
    ded.image = scanoutLocalImg;
    VkMemoryAllocateInfo mai{};
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext = &ded;
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = findMemType(props.memoryTypeBits, 0);
    if (vk_.AllocateMemory(device, &mai, nullptr, &scanoutLocalMem) != VK_SUCCESS ||
        vk_.BindImageMemory(device, scanoutLocalImg, scanoutLocalMem, 0) != VK_SUCCESS) {
        if (scanoutLocalMem != VK_NULL_HANDLE) {
            vk_.FreeMemory(device, scanoutLocalMem, nullptr);
            scanoutLocalMem = VK_NULL_HANDLE;
        }
        vk_.DestroyImage(device, scanoutLocalImg, nullptr);
        scanoutLocalImg = VK_NULL_HANDLE;
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
        return false;
    }

    scanoutLocalW = w;
    scanoutLocalH = h;
    scanoutLocalAhbFormat = ahbFormat;
    return true;
}

void VulkanRendererContext::applyScanoutBuffer() {
    if (!scanoutPendingDirty.load(std::memory_order_acquire)) return;
    ScanoutPending p;
    { std::lock_guard<std::mutex> lk(scanoutMutex);
      if (!scanoutPendingDirty.load()) return;
      p = scanoutPending;
      scanoutPending.acquireFenceFd = -1; // ownership transferred to p
      scanoutPendingDirty.store(false, std::memory_order_relaxed); }
    AHardwareBuffer* ahb=p.ahb; int w=p.w, h=p.h; (void)p.x; (void)p.y;
    int acquireFd = p.acquireFenceFd;
    if (!ahb || !scanoutGameSC) {
        if (acquireFd >= 0) close(acquireFd);
        /* Critical: send MSG_RELEASE for this slot even though we couldn't
         * apply the transaction. Otherwise Wine's AHB pool counts this
         * slot as "in flight" forever — and after a few such skipped
         * frames (4 with our default pool size), vkAcquireNextImageKHR
         * blocks on pthread_cond_wait indefinitely. That's the freeze
         * we hit when:
         *   (a) the user locks the device — DirectCompositor.onPause
         *       detaches scanoutGameSC, leaving us in this SKIPPED branch
         *       for every frame until onResume reattaches.
         *   (b) the user leaves a game idle for several minutes — Android
         *       lifecycle events (battery optimizer, screen timeout) fire
         *       transient onPause/onResume cycles that flip scanoutGameSC
         *       to null for brief windows. Even a few-millisecond detach
         *       can leak enough slots to deadlock the pool.
         *
         * The released slot is marked as a non-display release
         * (displayed=0) so the layer's WaitForPresentKHR pacing logic
         * doesn't tick the display counter for a frame that was never
         * actually shown. */
        if (p.slotIndex >= 0) {
            int sockFd = scanoutSocketFd.load(std::memory_order_relaxed);
            if (sockFd >= 0) {
                struct { uint8_t type; uint32_t slot_index; int32_t release_fd;
                         uint8_t displayed; uint64_t vsync_time_ns; } rel{};
                rel.type = 2; // MSG_RELEASE
                rel.slot_index = (uint32_t)p.slotIndex;
                rel.release_fd = -1;
                rel.displayed = 0;
                send(sockFd, &rel, sizeof(rel), MSG_NOSIGNAL);
            }
        }
        return;
    }

    int32_t cw = containerWidth  > 0 ? containerWidth  : w;
    int32_t ch = containerHeight > 0 ? containerHeight : h;
    ARect src{0, 0, cw, ch};
    ARect dst;
    if (scanoutDstW > 0 && scanoutDstH > 0) {
        dst = {scanoutDstX, scanoutDstY, scanoutDstX+scanoutDstW, scanoutDstY+scanoutDstH};
    } else {
        dst = {0, 0, cw, ch};
    }

    AHardwareBuffer* presentAhb = ahb;

    /* === P3: Direct passthrough with CPU-side fence wait ===
     *
     * When the source AHB is overlay-capable AND has BGRA byte layout
     * (matching SurfaceFlinger's read semantics), skip the receiver-side
     * GPU blit entirely. The cost of that blit was the dominant FPS
     * bottleneck (~3-4ms per frame on the Odin 2's Adreno). Without it,
     * the frame goes straight from DXVK → AHB → SurfaceFlinger overlay.
     *
     * Why CPU-poll instead of trusting the acquire fence at setBuffer:
     * Odin 2's HWC empirically doesn't honor the acquire-fence-fd when
     * scanning out overlays — earlier direct-passthrough experiments
     * produced visible scanline tearing. By polling the fence FD on the
     * CPU here (blocks until the kernel signals GPU completion), we
     * guarantee the buffer is fully written before we tell SF to display
     * it. SF can still wait on the fence too (it's a no-op since we
     * already CPU-confirmed it's signaled) — defense in depth.
     *
     * Conditions to engage:
     *   - p.bgraBytes != 0: source AHB has BGRA byte layout (pool default).
     *   - !scanoutNeedsGpuBlit: source AHB has COMPOSER_OVERLAY (confirmed
     *     on first frame).
     * Otherwise fall back to the local-blit path for safety. */
    const bool canDirectPassthrough = (p.bgraBytes != 0) && !scanoutNeedsGpuBlit;
    if (canDirectPassthrough) {
        if (acquireFd >= 0) {
            /* poll() with POLLIN signals when the SYNC_FD is ready (kernel
             * fence signaled). Tight timeout — if the fence hangs, we
             * shouldn't stall display forever. The fd stays valid and
             * gets passed to ST_SETBUF below (defense in depth). */
            struct pollfd pfd{};
            pfd.fd = acquireFd;
            pfd.events = POLLIN;
            int rc = poll(&pfd, 1, 30 /* ms */);
            if (rc < 0) {
                RLOG("applyScanoutBuffer: poll(acquire_fd) errno=%d", errno);
            } else if (rc == 0) {
                RLOG("applyScanoutBuffer: poll(acquire_fd) TIMEOUT after 30ms — proceeding anyway");
            }
        }
        /* presentAhb stays = source ahb. No local blit. SF gets the AHB
         * directly with COMPOSER_OVERLAY usage, eligible for hardware
         * overlay scanout. */
    } else if (scanoutAlwaysGpuBlit || scanoutNeedsGpuBlit) {
        AHardwareBuffer_Desc srcDesc{};
        AHardwareBuffer_describe(ahb, &srcDesc);
        if (ensureScanoutLocalAhb((int)srcDesc.width, (int)srcDesc.height, srcDesc.format)) {
            /* Import the source AHB as B8G8R8A8 in direct-render mode (DXVK
             * wrote BGRA bytes into a HAL_RGBA AHB), or default-via-swapRB
             * otherwise. The subsequent blit uses format-aware reinterpretation
             * (BlitImage) to swap R↔B when needed. */
            const VkFormat srcImportFormat = (p.bgraBytes != 0)
                ? VK_FORMAT_B8G8R8A8_UNORM
                : VK_FORMAT_UNDEFINED;

            VkImage srcImg = VK_NULL_HANDLE;
            auto it = ahbTexCache.find(ahb);
            if (it != ahbTexCache.end()) {
                srcImg = it->second.img;
            } else {
                WinTex tmp{};
                if (importAHBToWinTex(tmp, ahb, srcImportFormat)) {
                    AHBCached cached{tmp.img, tmp.mem, tmp.view, tmp.ds};
                    AHardwareBuffer_acquire(ahb);
                    ahbTexCache[ahb] = cached;
                    ahbCacheLRU.push_back(ahb);
                    srcImg = cached.img;
                }
            }

            if (srcImg != VK_NULL_HANDLE && scanoutLocalImg != VK_NULL_HANDLE &&
                scanoutBlitCb != VK_NULL_HANDLE && scanoutBlitFence != VK_NULL_HANDLE) {
                vk_.WaitForFences(device, 1, &scanoutBlitFence, VK_TRUE, UINT64_MAX);
                vk_.ResetFences(device, 1, &scanoutBlitFence);
                vk_.ResetCommandBuffer(scanoutBlitCb, 0);

                VkCommandBufferBeginInfo bi{};
                bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                vk_.BeginCommandBuffer(scanoutBlitCb, &bi);

                transition(scanoutBlitCb, srcImg,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                transition(scanoutBlitCb, scanoutLocalImg,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

                if (p.bgraBytes != 0) {
                    /* Direct-render mode: format-aware blit from B8G8R8A8 src
                     * to R8G8B8A8 dst handles the R↔B swap during the copy.
                     * Slightly slower than vkCmdCopyImage on Adreno (uses
                     * shader path) but uniform across formats. */
                    VkImageBlit region{};
                    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    region.srcOffsets[0] = {0, 0, 0};
                    region.srcOffsets[1] = {(int32_t)w, (int32_t)h, 1};
                    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    region.dstOffsets[0] = {0, 0, 0};
                    region.dstOffsets[1] = {(int32_t)w, (int32_t)h, 1};
                    vk_.CmdBlitImage(scanoutBlitCb,
                        srcImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        scanoutLocalImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        1, &region, VK_FILTER_NEAREST);
                } else {
                    /* Trojan-blit mode: AHB already has correct RGBA byte order.
                     * Plain byte copy, fastest path on Adreno. */
                    VkImageCopy region{};
                    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    region.extent = {(uint32_t)w, (uint32_t)h, 1};
                    vk_.CmdCopyImage(scanoutBlitCb,
                        srcImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        scanoutLocalImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        1, &region);
                }
                vk_.EndCommandBuffer(scanoutBlitCb);

                VkSubmitInfo si{};
                si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                si.commandBufferCount = 1;
                si.pCommandBuffers = &scanoutBlitCb;
                vk_.QueueSubmit(graphicsQueue, 1, &si, scanoutBlitFence);
                presentAhb = scanoutLocalAhb;
            }
        }
    }

    void* t=ST_CREATE();
    ST_SETBUF(t,scanoutGameSC,presentAhb,acquireFd);
    ST_SETGEO(t,scanoutGameSC,&src,&dst,0);
    ST_SETVIS(t,scanoutGameSC,1);
    ST_SETBP(t,scanoutGameSC,false);

    /*
     * Adaptive frame-rate hint to SurfaceFlinger.
     *
     * On LTPO displays (OnePlus 12R: 1–120 Hz) SurfaceFlinger chooses the
     * panel refresh rate based on per-layer hints. Without a hint it defaults
     * to the panel maximum (120 Hz), wasting power when a game runs at 60.
     * With an incorrect fixed hint (initScanout always requests 120 Hz) it
     * may also stutter if the game actually delivers 90 fps.
     *
     * We read the EMA-smoothed vsync period that the release-reader thread
     * maintains from MSG_VSYNC deltas, convert it to fps, and update the
     * hint every 60 frames (~0.5–1 s). The frame-rate compatibility=0
     * (COMPATIBILITY_DEFAULT) lets SF pick the nearest panel rate it
     * supports — so 58 fps → 60 Hz, 88 fps → 90 Hz, 118 fps → 120 Hz.
     *
     * g_vsync_period_us_measured is defined in ahb_layer.c and updated
     * by the release-reader thread (no mutex needed here — reading a 64-bit
     * value written by another thread is safe on ARM64 with relaxed ordering
     * for this advisory purpose).
     */
    extern uint64_t g_vsync_period_us_measured;
    if (fnSTSetFrameRate) {
        static int frameRateUpdateCounter = 0;
        if (++frameRateUpdateCounter >= 60) {
            frameRateUpdateCounter = 0;
            uint64_t periodUs = g_vsync_period_us_measured;
            if (periodUs > 0) {
                float fps = 1000000.0f / (float)periodUs;
                /* Clamp to sane range — don't hint below 24 or above 144. */
                if (fps < 24.0f)  fps = 24.0f;
                if (fps > 144.0f) fps = 144.0f;
                typedef void (*pfn_STSetFrameRate)(void*, void*, float, int8_t);
                ((pfn_STSetFrameRate)fnSTSetFrameRate)(t, scanoutGameSC,   fps, 0);
                ((pfn_STSetFrameRate)fnSTSetFrameRate)(t, scanoutCursorSC, fps, 0);
                RLOG("applyScanoutBuffer: adaptive setFrameRate %.1f Hz (period=%llu us)",
                     fps, (unsigned long long)periodUs);
            }
        }
    }

    // Two callbacks here serve two distinct purposes:
    //
    //  1. onCommit (API 31+, preferred): fires ~1 vsync after apply, at the
    //     moment SurfaceFlinger latches the buffer for the next frame.
    //     Sends MSG_TICK (type=5). The layer's release-reader thread uses
    //     this to advance g_display_count, which drives vkWaitForPresentKHR's
    //     pacing wait — locking DXVK to real panel vsync without the 2-vsync
    //     latency that plain onComplete-driven pacing imposes.
    //
    //  2. onComplete: fires later, when the previous buffer is no longer
    //     in use by SurfaceFlinger. Sends MSG_RELEASE (slot freeing only,
    //     displayed=0 so we don't double-tick alongside onCommit). If
    //     onCommit is unavailable (older Android), this falls back to
    //     displayed=1 so the existing tick path keeps working.
    static int scanoutPrevDisplayedSlot = -1;
    int sockFd = scanoutSocketFd.load(std::memory_order_relaxed);

    // Register onCommit on the CURRENT transaction. It will fire when
    // *this* slot's buffer reaches the display. The context only needs the
    // socket fd; we tick once per commit regardless of slot identity.
    if (fnSTSetOnCommit && sockFd >= 0) {
        typedef void (*pfn_STSetOnCommit)(void*, void*, void(*)(void*, void*));
        /* Extended context: carry a back-pointer to the renderer + the slot
         * being committed so the callback can compute T2 - T1 and update
         * the latency EMA. Both are read inside the callback. */
        struct OnCommitCtx { int socketFd; VulkanRendererContext* renderer; int slotIndex; };
        auto* ctx = new OnCommitCtx{sockFd, this, p.slotIndex};
        ((pfn_STSetOnCommit)fnSTSetOnCommit)(t, (void*)ctx,
            [](void* context, void* stats) {
                auto* c = reinterpret_cast<OnCommitCtx*>(context);
                /* Capture T2 once — used by both the jitter trace AND the
                 * compositor-latency EMA update below. */
                struct timespec ts;
                clock_gettime(CLOCK_MONOTONIC, &ts);
                uint64_t now_us = (uint64_t)ts.tv_sec * 1000000ULL
                                + (uint64_t)ts.tv_nsec / 1000ULL;

                /* JITTER TRACE: SurfaceFlinger's onCommit firing rate.
                 * Compare against layer-side stage=tick to measure socket
                 * latency from SF → Android receiver → Wine release-reader. */
                {
                    static uint64_t s_commit_prev = 0;
                    uint64_t prev = s_commit_prev;
                    s_commit_prev = now_us;
                    if (prev) {
                        __android_log_print(ANDROID_LOG_INFO, "Winlator_Scanout",
                            "JITTER_TRACE: stage=onCommit delta_us=%llu",
                            (unsigned long long)(now_us - prev));
                    }
                }

                /* === COMPOSITOR LATENCY ===
                 * T1 = recv-thread MSG_PRESENT timestamp stored in
                 *      renderer->latencyArriveUs[slot]
                 * T2 = now_us (this callback fires when SF latches the
                 *      buffer at the next vsync — the "apply tick")
                 * Update an EMA so the HUD can read a smoothed single number.
                 * Drop outliers > 500 ms which would skew the EMA on first-
                 * frame anomalies or pause windows. */
                if (c->renderer
                    && c->slotIndex >= 0
                    && c->slotIndex < VulkanRendererContext::LATENCY_SLOT_MAX) {
                    uint64_t arrive_us = c->renderer->latencyArriveUs[c->slotIndex]
                        .load(std::memory_order_relaxed);
                    if (arrive_us != 0 && now_us > arrive_us) {
                        uint64_t delta = now_us - arrive_us;
                        if (delta < 500000ULL) {
                            uint64_t prev = c->renderer->latencyEmaUs
                                .load(std::memory_order_relaxed);
                            /* 1/8 weight on new sample → ~8-frame smoothing */
                            uint64_t next = (prev == 0)
                                ? delta
                                : (prev * 7 + delta) / 8;
                            c->renderer->latencyEmaUs
                                .store(next, std::memory_order_relaxed);
                        }
                    }
                }

                /* Send a MSG_TICK packet using the release_msg wire format.
                 * Layout MUST match struct release_msg in vulkan_ahb.h EXACTLY,
                 * including the trailing vsync_time_ns field (added with the
                 * Choreographer phase-anchor work). The Wine-side socket is
                 * SOCK_STREAM, so any size mismatch shifts subsequent messages
                 * and corrupts the entire stream → slots stop releasing →
                 * black screen. Zero-init the new field; only MSG_VSYNC reads it. */
                struct { uint8_t type; uint32_t slot_index; int32_t release_fd; uint8_t displayed; uint64_t vsync_time_ns; } tick{};
                tick.type = 5; // MSG_TICK
                send(c->socketFd, &tick, sizeof(tick), MSG_NOSIGNAL);
                delete c;
            });
    }

    // onComplete: free the previously-displayed slot.
    if (fnSTSetOnComplete && scanoutPrevDisplayedSlot >= 0 && sockFd >= 0) {
        struct OnCompleteCtx {
            int socketFd;
            int slotIndex;
            int tickFromComplete;  /* 1 if we should also tick here (no onCommit) */
        };
        auto* ctx = new OnCompleteCtx{
            sockFd, scanoutPrevDisplayedSlot,
            fnSTSetOnCommit ? 0 : 1
        };
        ((pfn_STSetOnComplete)fnSTSetOnComplete)(t, (void*)ctx,
            [](void* context, void* stats) {
                auto* c = reinterpret_cast<OnCompleteCtx*>(context);
                /* Layout MUST match struct release_msg in vulkan_ahb.h EXACTLY
                 * (including the trailing vsync_time_ns field used by MSG_VSYNC).
                 * SOCK_STREAM has no record boundaries — any size drift shifts
                 * every subsequent message. */
                struct { uint8_t type; uint32_t slot_index; int32_t release_fd; uint8_t displayed; uint64_t vsync_time_ns; } rel{};
                rel.type = 2; // MSG_RELEASE
                rel.slot_index = (uint32_t)c->slotIndex;
                rel.release_fd = -1;
                /* If onCommit isn't available, displayed=1 keeps the layer's
                 * old pacing path working (just with the 2-vsync latency).
                 * If onCommit IS available, MSG_TICK already advanced
                 * g_display_count, so displayed=0 (slot free only). */
                rel.displayed = (uint8_t)c->tickFromComplete;
                send(c->socketFd, &rel, sizeof(rel), MSG_NOSIGNAL);
                delete c;
            });
    }
    scanoutPrevDisplayedSlot = p.slotIndex;

    ST_APPLY(t);
    /* directFrameCount used to be incremented HERE (per displayed frame)
     * which gave DAC a panel-rate counter (≤ 60 FPS on a 60 Hz panel)
     * while Native used a Wine-frame-rate counter — making the two modes
     * report inherently incomparable FPS even when DXVK was producing at
     * the same rate. Moved to the recv thread in vulkan_jni.cpp where it
     * fires once per MSG_PRESENT (= once per Wine frame), making the
     * DAC reading semantically match Native's hud.onFrame() count. */
    gameFrameDelivered.store(true);
    ST_DELETE(t);
    // AHardwareBuffer_release removed: no matching acquire, pool owns the ref
}

void VulkanRendererContext::scanoutSetDst(int x, int y, int w, int h) {
    scanoutDstX=x; scanoutDstY=y; scanoutDstW=w; scanoutDstH=h;
}

std::pair<int,int> VulkanRendererContext::pollReleaseFence() {
    std::lock_guard<std::mutex> lk(releaseMutex);
    if (releaseQueue.empty()) return {-1, -1};
    auto front = releaseQueue.front();
    releaseQueue.erase(releaseQueue.begin());
    return {front.slotIndex, front.releaseFd};
}

void VulkanRendererContext::scanoutSetCursorImage(void* pixels, short w, short h, short stride) {
    if (!scanoutActive.load() || !scanoutCursorSC || !pixels || w<=0 || h<=0) return;
    if (stride <= 0) stride = w;

    auto* curBuf = reinterpret_cast<AHardwareBuffer**>(&scanoutCursorBuf);
    if (*curBuf && (scanoutCursorBufW != w || scanoutCursorBufH != h)) {
        AHardwareBuffer_release(*curBuf);
        *curBuf = nullptr;
    }
    if (!*curBuf) {
        AHardwareBuffer_Desc d{};
        d.width  = (uint32_t)w; d.height = (uint32_t)h; d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage  = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                   AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                   AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        if (AHardwareBuffer_allocate(&d, curBuf) != 0) return;
        scanoutCursorBufW = w; scanoutCursorBufH = h;
    }

    AHardwareBuffer_Desc dstDesc{};
    AHardwareBuffer_describe(*curBuf, &dstDesc);
    uint32_t dstStride = dstDesc.stride;

    void* dst = nullptr;
    if (AHardwareBuffer_lock(*curBuf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            -1, nullptr, &dst) != 0) return;
    const uint32_t* src = reinterpret_cast<const uint32_t*>(pixels);
    auto* dstPx = reinterpret_cast<uint32_t*>(dst);
    for (int row = 0; row < h; ++row) {
        memcpy(dstPx + (size_t)row * dstStride,
               src + (size_t)row * (uint32_t)stride,
               (size_t)w * 4);
    }
    AHardwareBuffer_unlock(*curBuf, nullptr);

    void* t = ST_CREATE();
    ST_SETBUF(t, scanoutCursorSC, *curBuf, -1);
    ST_APPLY(t);
    ST_DELETE(t);
}

void VulkanRendererContext::scanoutSetCursorPos(short x, short y, short hotX, short hotY) {
    if (!scanoutActive.load() || !scanoutCursorSC) return;
    if (scanoutCursorBufW <= 0 || scanoutCursorBufH <= 0) return;

    int32_t cw = containerWidth  > 0 ? containerWidth  : surfaceWidth;
    int32_t ch = containerHeight > 0 ? containerHeight : surfaceHeight;
    int32_t dx = scanoutDstW > 0 ? scanoutDstX : 0;
    int32_t dy = scanoutDstW > 0 ? scanoutDstY : 0;
    int32_t dw = scanoutDstW > 0 ? scanoutDstW : cw;
    int32_t dh = scanoutDstW > 0 ? scanoutDstH : ch;

    float fx = dx + ((float)x / cw) * dw;
    float fy = dy + ((float)y / ch) * dh;
    float scaleW = (float)dw / cw;
    float scaleH = (float)dh / ch;
    int32_t curW = (int32_t)(scanoutCursorBufW * scaleW);
    int32_t curH = (int32_t)(scanoutCursorBufH * scaleH);
    int32_t cx = std::max(0, (int32_t)(fx - hotX * scaleW));
    int32_t cy = std::max(0, (int32_t)(fy - hotY * scaleH));

    ARect src{0, 0, scanoutCursorBufW, scanoutCursorBufH};
    ARect dst{cx, cy, cx + curW, cy + curH};

    void* t = ST_CREATE();
    ST_SETGEO(t, scanoutCursorSC, &src, &dst, 0);
    ST_SETVIS(t, scanoutCursorSC, 1);
    ST_APPLY(t);
    ST_DELETE(t);
}

void VulkanRendererContext::dumpRendererInfo() {
    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice,&props);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "=== RENDERER INFO ===");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "GPU: %s vendorID=0x%x driverVersion=0x%x apiVersion=%d.%d.%d",
        props.deviceName,props.vendorID,props.driverVersion,
        VK_VERSION_MAJOR(props.apiVersion),VK_VERSION_MINOR(props.apiVersion),VK_VERSION_PATCH(props.apiVersion));
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Swapchain: %dx%d fmt=%d",swapchainExt.width,swapchainExt.height,(int)swapchainFmt);
    std::string pmList;
    for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "SupportedPresentModes: [%s] current=%d",pmList.c_str(),(int)requestedPresentMode);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Filter: mode=%d (%s)", filterMode, filterMode==2?(cubicSupported?"CUBIC":"LINEAR"):filterMode==1?"NEAREST":"LINEAR");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Scanout: active=%d gameFrameDelivered=%d scanoutGameSC=%p",
        (int)scanoutActive.load(),(int)gameFrameDelivered.load(),scanoutGameSC);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "DirectCompositing: directFrameCount=%llu releaseQueueSize=%zu",
        (unsigned long long)directFrameCount.load(),
        [this]{ std::lock_guard<std::mutex> lk(releaseMutex); return releaseQueue.size(); }());
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Surface: %dx%d container: %dx%d",
        surfaceWidth,surfaceHeight,containerWidth,containerHeight);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,"=== END RENDERER INFO ===");
}

void VulkanRendererContext::setFilterMode(int mode) {
    RLOG("setFilterMode: %d -> %d (%s->%s)", filterMode, mode,
        filterMode==2?(cubicSupported?"CUBIC":"LINEAR"):filterMode==1?"NEAREST":"LINEAR", mode==2?(cubicSupported?"CUBIC":"LINEAR"):mode==1?"NEAREST":"LINEAR");
    if (filterMode==mode) { RLOG("setFilterMode: already set, skipping"); return; }
    filterMode=mode;
    vk_.DeviceWaitIdle(device);
    if (sampler!=VK_NULL_HANDLE){vk_.DestroySampler(device,sampler,nullptr);sampler=VK_NULL_HANDLE;}
    createSampler();
    auto updateDS=[&](VkDescriptorSet ds, VkImageView view){
        if(ds==VK_NULL_HANDLE||view==VK_NULL_HANDLE) return;
        VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        dii.imageView=view; dii.sampler=sampler;
        VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr.dstSet=ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wr.descriptorCount=1; wr.pImageInfo=&dii;
        vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    };
    int dsCount=0;
    for (auto& [id,wt]:texMap) { updateDS(wt.ds, wt.view); if(wt.ds!=VK_NULL_HANDLE) dsCount++; }
    for (auto& [ahb,cached]:ahbTexCache) { updateDS(cached.ds, cached.view); if(cached.ds!=VK_NULL_HANDLE) dsCount++; }
    if (cursorDS!=VK_NULL_HANDLE&&cursorView!=VK_NULL_HANDLE) { updateDS(cursorDS, cursorView); dsCount++; }
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::setSwapRB(bool enabled) {
    if (swapRB == enabled) return;
    swapRB = enabled;
    scanoutAlwaysGpuBlit = scanoutEnvGpuBlit || swapRB;
    if (scanoutAlwaysGpuBlit) scanoutNeedsGpuBlit = true;
    RLOG("setSwapRB: %d (alwaysGpuBlit=%d)", (int)swapRB, (int)scanoutAlwaysGpuBlit);
    if (scanoutActive.load()) {
        SCANOUT_LOG("setSwapRB: native swapRB requires GPU blit path (alwaysGpuBlit=%d)",
            (int)scanoutAlwaysGpuBlit);
    }
    needsRender.store(true);
    dirtyCV.notify_one();
}

void VulkanRendererContext::setEffect(int effectId, float sharpness) {
    activeEffectId = effectId;
    activeSharpness = std::max(0.0f, std::min(1.0f, sharpness));
    RLOG("setEffect: id=%d sharpness=%.3f", activeEffectId, activeSharpness);
    needsRender.store(true);
    dirtyCV.notify_one();
}

void VulkanRendererContext::setPresentMode(VkPresentModeKHR mode) {
    bool supported = false;
    for (auto pm : availablePresentModes) if (pm == mode) { supported = true; break; }
    VkPresentModeKHR target = supported ? mode : VK_PRESENT_MODE_FIFO_KHR;
    RLOG("setPresentMode: requested=%d supported=%d -> applying=%d",
        (int)mode, (int)supported, (int)target);
    if (requestedPresentMode==target) { RLOG("setPresentMode: already set, skipping"); return; }
    requestedPresentMode=target;
    fbResized.store(true); dirtyCV.notify_one();
}

#pragma GCC diagnostic pop
