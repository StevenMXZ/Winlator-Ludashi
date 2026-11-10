#!/usr/bin/env python3
"""Compile production timestamp handling against a nonblocking Vulkan model."""
import os
import pathlib
import subprocess
import tempfile
root=pathlib.Path(__file__).resolve().parents[6]
renderer=root/'app/src/main/cpp/winlator/renderer/vulkan'
source=(renderer/'VulkanRendererContext.cpp').read_text()
functions=source[source.index('void VulkanRendererContext::prepareNativeGpuTiming('):source.index('void VulkanRendererContext::reportNativeMetrics(')]
fixture=r'''
#include "native_composition.h"
#include <vector>
#include <cassert>
#include <cstring>
#include <iostream>
using VkCommandBuffer=uint64_t; using VkQueryPool=uint64_t; using VkResult=int;
constexpr uint64_t VK_NULL_HANDLE=0;
constexpr int VK_SUCCESS=0, VK_NOT_READY=1, MAX_FRAMES_IN_FLIGHT=2;
constexpr int VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO=1,VK_QUERY_TYPE_TIMESTAMP=2;
constexpr int VK_QUERY_RESULT_64_BIT=1, VK_QUERY_RESULT_WITH_AVAILABILITY_BIT=2;
constexpr int VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT=1,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT=2,ANDROID_LOG_INFO=1;
void __android_log_print(int,const char*,const char*,...) {}
struct VkPhysicalDeviceProperties { const char* deviceName="model"; uint32_t driverVersion{}; struct {float timestampPeriod{.5f};} limits; };
struct VkQueueFamilyProperties { uint32_t timestampValidBits{36}; };
struct VkQueryPoolCreateInfo { int sType{},queryType{}; uint32_t queryCount{}; };
uint32_t bits=36; bool available=true; int reads{},creates{},writes{},resets{}; uint32_t firstRead{};
void Properties(int,VkPhysicalDeviceProperties* out) { *out={}; }
void Queues(int,uint32_t* count,VkQueueFamilyProperties* out) { *count=1; if(out) out->timestampValidBits=bits; }
int Create(int,const VkQueryPoolCreateInfo* info,void*,uint64_t* out) { assert(info->queryCount==4); ++creates; *out=7; return 0; }
void Destroy(int,uint64_t,void*) {}
int Results(int,uint64_t,uint32_t first,uint32_t count,size_t bytes,void* data,size_t stride,int flags) {
    ++reads; firstRead=first;
    assert(count==2 && bytes==32 && stride==16 && flags==3); // No WAIT_BIT.
    uint64_t result[4]{(uint64_t(1)<<36)-10,available?1u:0u,5,available?1u:0u};
    std::memcpy(data,result,sizeof(result)); return available?0:1;
}
void Reset(uint64_t,uint64_t,uint32_t first,uint32_t count) { ++resets; assert(first==2 && count==2); }
void Write(uint64_t,int stage,uint64_t,uint32_t query) { ++writes; assert((stage==1 && query==2)||(stage==2 && query==3)); }
class VulkanRendererContext {
public:
    struct {
        decltype(&Properties) GetPhysicalDeviceProperties{Properties};
        decltype(&Queues) GetPhysicalDeviceQueueFamilyProperties{Queues};
        decltype(&Create) CreateQueryPool{Create}; decltype(&Destroy) DestroyQueryPool{Destroy};
        decltype(&Results) GetQueryPoolResults{Results};
        decltype(&Reset) CmdResetQueryPool{Reset}; decltype(&Write) CmdWriteTimestamp{Write};
    } vk_;
    int physicalDevice{},device{}; uint32_t graphicsQueueFamilyIndex{},currentFrame{1};
    bool nativeGpuAttempted{},nativeDiagnosticsEnabled{true};
    VkQueryPool nativeGpuQueries{}; uint32_t nativeTimestampBits{}; float nativeTimestampPeriod{};
    std::array<bool,2> nativeGpuPending{}; NativeVulkanMetrics nativeMetrics;
    void prepareNativeGpuTiming(uint32_t);
    void beginNativeGpuTiming(VkCommandBuffer);
    void endNativeGpuTiming(VkCommandBuffer);
};
'''
tests=r'''
int main() {
    VulkanRendererContext f;
    f.prepareNativeGpuTiming(1); assert(creates==1 && reads==0);
    f.beginNativeGpuTiming(100); f.endNativeGpuTiming(100); assert(resets==1 && writes==2);
    f.nativeGpuPending[1]=true; f.prepareNativeGpuTiming(1);
    assert(firstRead==2 && reads==1 && f.nativeMetrics.gpu.count==1 && f.nativeMetrics.gpu.total==7); // 36-bit wrap, 0.5ns period.
    f.prepareNativeGpuTiming(1); assert(reads==1); // No duplicate samples.
    available=false; f.nativeGpuPending[0]=true; f.prepareNativeGpuTiming(0);
    assert(reads==2 && firstRead==0 && f.nativeMetrics.gpuUnavailable==1 && !f.nativeGpuPending[0]);
    f.nativeDiagnosticsEnabled=false; f.beginNativeGpuTiming(100); f.endNativeGpuTiming(100);
    assert(resets==1 && writes==2);
    bits=0; VulkanRendererContext unsupported;
    unsupported.prepareNativeGpuTiming(0); unsupported.beginNativeGpuTiming(100);
    assert(creates==1 && unsupported.nativeGpuQueries==0 && resets==1);
    bits=64; VulkanRendererContext missing; missing.vk_.CreateQueryPool=nullptr;
    missing.prepareNativeGpuTiming(0); assert(missing.nativeGpuQueries==0);
    std::cout << "PASS: production GPU timestamps, slot indices, availability, wrap, period, unsupported-driver and disabled diagnostics paths without waits\n";
}
'''
with tempfile.TemporaryDirectory(prefix='ludashi-gpu-timing-') as directory:
    unit=pathlib.Path(directory)/'test.cpp'; unit.write_text(fixture+functions+tests)
    binary=pathlib.Path(directory)/'test'
    subprocess.run([os.environ.get('CXX','g++'),'-std=c++17','-O1','-g','-fsanitize=address,undefined',
                    '-fno-omit-frame-pointer','-I'+str(renderer),str(unit),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
