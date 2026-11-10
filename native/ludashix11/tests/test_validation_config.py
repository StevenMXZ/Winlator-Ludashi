#!/usr/bin/env python3
"""Exercise the production opt-in layer/extension/pNext configuration block."""
import pathlib, subprocess, tempfile

root = pathlib.Path(__file__).resolve().parents[3]
source = (root / 'app/src/main/cpp/winlator/renderer/vulkan/VulkanRendererContext.cpp').read_text()
begin = source.index('    const char* validationLayer = "VK_LAYER_KHRONOS_validation";')
end = source.index('    VkResult result = vk_.CreateInstance', begin)
configuration = source[begin:end]
preamble = r'''
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <vector>
constexpr int VK_SUCCESS=0;
constexpr const char* VK_EXT_DEBUG_UTILS_EXTENSION_NAME="VK_EXT_debug_utils";
constexpr const char* VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME="VK_EXT_validation_features";
constexpr int VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT=1, VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT=2;
constexpr int VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT=1, VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT=2;
constexpr int VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT=1, VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT=2,
              VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT=4;
enum VkValidationFeatureEnableEXT { VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT=4 };
int logValidationMessage() { return 0; }
struct VkLayerProperties { char layerName[256]{}; };
struct VkExtensionProperties { char extensionName[256]{}; };
struct VkDebugUtilsMessengerCreateInfoEXT { int sType{}; const void* pNext{}; int messageSeverity{},messageType{};
    decltype(&logValidationMessage) pfnUserCallback{}; };
struct VkValidationFeaturesEXT { int sType{}; const void* pNext{}; uint32_t enabledValidationFeatureCount{};
    const VkValidationFeatureEnableEXT* pEnabledValidationFeatures{}; };
struct CreateInfo { uint32_t enabledLayerCount{},enabledExtensionCount{2}; const char* const* ppEnabledLayerNames{};
    const char* const* ppEnabledExtensionNames{}; const void* pNext{}; };
using PFN_vkEnumerateInstanceLayerProperties=int(*)(uint32_t*,VkLayerProperties*);
using PFN_vkEnumerateInstanceExtensionProperties=int(*)(const char*,uint32_t*,VkExtensionProperties*);
bool layerAvailable{},debugAvailable{},syncAvailable{}; unsigned layerQueries{},extensionQueries{};
int Layers(uint32_t* count,VkLayerProperties* layers) {
    ++layerQueries; *count=layerAvailable?1:0;
    if(layers&&layerAvailable) std::strcpy(layers[0].layerName,"VK_LAYER_KHRONOS_validation");
    return VK_SUCCESS;
}
int Extensions(const char* layer,uint32_t* count,VkExtensionProperties* properties) {
    ++extensionQueries; std::vector<const char*> names;
    if(debugAvailable) names.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    if(syncAvailable&&layer) names.push_back(VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME);
    *count=names.size();
    if(properties)for(size_t i=0;i<names.size();++i)std::strcpy(properties[i].extensionName,names[i]);
    return VK_SUCCESS;
}
void* gipa(void*,const char* name) {
    if(!std::strcmp(name,"vkEnumerateInstanceLayerProperties"))return reinterpret_cast<void*>(Layers);
    if(!std::strcmp(name,"vkEnumerateInstanceExtensionProperties"))return reinterpret_cast<void*>(Extensions);
    return nullptr;
}
#define RLOG_E(...) do{}while(0)
void Run(bool validationRequested,bool layer,bool debug,bool sync) {
    layerAvailable=layer;debugAvailable=debug;syncAvailable=sync;layerQueries=extensionQueries=0;
    const char* ext[]={"VK_KHR_surface","VK_KHR_android_surface",VK_EXT_DEBUG_UTILS_EXTENSION_NAME,nullptr};
    CreateInfo ci;ci.ppEnabledExtensionNames=ext;
'''
checks = r'''
    const bool expectedValidation=validationRequested&&layer;
    const bool expectedDebug=expectedValidation&&debug;
#ifdef LUDASHI_NATIVE_X11
    const bool expectedSync=expectedValidation&&sync;
    if(expectedSync){assert(ci.pNext==&validationFeatures);
        assert(validationFeatures.enabledValidationFeatureCount==1);
        assert(*validationFeatures.pEnabledValidationFeatures==VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT);
        assert(validationFeatures.pNext==(expectedDebug?&debugInfo:nullptr));}
#else
    const bool expectedSync=false;
#endif
    assert(enableValidation==expectedValidation&&enableDebugUtils==expectedDebug);
    assert(ci.enabledLayerCount==unsigned(expectedValidation));
    assert(ci.enabledExtensionCount==2+unsigned(expectedDebug)+unsigned(expectedSync));
    if(!expectedSync)assert(ci.pNext==(expectedDebug?&debugInfo:nullptr));
    if(!validationRequested)assert(layerQueries==0&&extensionQueries==0);
    if(!expectedValidation)assert(extensionQueries==0);
    for(uint32_t i=0;i<ci.enabledExtensionCount;++i)assert(ext[i]);
}
int main(){
    Run(false,true,true,true); Run(false,false,false,false);
    Run(true,false,true,true); Run(true,true,true,true);
    Run(true,true,true,false); Run(true,true,false,true); Run(true,true,false,false);
    std::cout<<"PASS: production validation opt-in, extension discovery, pNext chains and missing-feature fallback\n";
}
'''
with tempfile.TemporaryDirectory(prefix='ludashi-validation-config-') as directory:
    folder=pathlib.Path(directory); path=folder/'test.cpp';path.write_text(preamble+configuration+checks)
    for native in (False,True):
        binary=folder/('native' if native else 'java')
        subprocess.run(['g++','-std=c++20','-O2','-Wall','-Wextra','-Werror',
                        *(['-DLUDASHI_NATIVE_X11=1'] if native else []),str(path),'-o',str(binary)],check=True)
        subprocess.run([str(binary)],check=True)
