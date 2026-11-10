#!/usr/bin/env python3
"""Compile the production command-selection block; no Android driver emulation."""
import os
import pathlib
import subprocess
import tempfile
root = pathlib.Path(__file__).resolve().parents[3]
renderer = root / 'app/src/main/cpp/winlator/renderer/vulkan'
source = (renderer / 'VulkanRendererContext.cpp').read_text()
start = source.index('    VkCommandBuffer selectedCommand = cmdBufs[currentFrame];')
end = source.index('    VkSemaphore wSem[]', start)
selection = source[start:end]
fixture = r'''
#include "native_command_cache.h"
#include "native_composition.h"
#include <vector>
#include <stdexcept>
#include <cassert>
#include <iostream>
using VkCommandBuffer = uint64_t;
constexpr uint64_t VK_NULL_HANDLE=0;
constexpr int MAX_FRAMES_IN_FLIGHT=2, VK_SUCCESS=0;
constexpr int VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO=1, VK_COMMAND_BUFFER_LEVEL_PRIMARY=0;
struct VkCommandBufferAllocateInfo { int sType{}; uint64_t commandPool{}; int level{}; int commandBufferCount{}; };
struct Fixture {
    struct Dispatch {
        uint64_t next{100}; int resets{}, allocations{}; bool fail{};
        int AllocateCommandBuffers(int,const VkCommandBufferAllocateInfo*,uint64_t* out) {
            ++allocations; *out=fail ? 0 : next++; return fail ? -1 : 0;
        }
        void ResetCommandBuffer(uint64_t,int) { ++resets; }
    } vk_;
    struct Draw { uint64_t img{20}, ds{30}, upload{}; int x{},y{},w{1280},h{720}; bool isAHB{true},needsTransition{}; };
    std::vector<Draw> frameDraws{Draw{}};
    NativeCommandCache<uint64_t,2> nativeCommands;
    uint64_t nativeTextureEpoch{}, nativeGpuQueries{};
    uint64_t pipeline{40},opaquePipeline{50},renderPass{60},nativeNoClearPass{70},cmdPool{80};
    uint64_t cmdBufs[2]{1,2}, cursorImg{},cursorDS{},curUpload{};
    bool nativeCommandCacheEnabled{true};
    bool useFrameGeneration{},hasCurUpload{},curVis{},swapRB{},nativeTrace{true},throwRecord{};
    int filterMode{},postFXMode{},stretchMode{},device{};
    uint32_t currentFrame{},imgIdx{},generatedCount{};
    float ox{},oy{},sx{1},sy{1},cw{1280},ch{720};
    int ptrX{},ptrY{},curHotX{},curHotY{},curW{},curH{};
    int frameAhbTransitions{},framePreUpload{},framePostUpload{};
    struct { struct {int x{},y{};} offset; struct {uint32_t width{1280},height{720};} extent; } effectiveScissor;
    NativeVulkanMetrics nativeMetrics;
    using NativeClock=NativeVulkanMetrics::Clock;
    NativeClock::time_point nativeRecordStart=NativeClock::now();
    int recordings{};
    template<class... Args> void recordCmdBuf(Args&&...) {
        ++recordings; if (throwRecord) throw std::runtime_error("record failed");
    }
    uint64_t Select() {
'''
tests = r'''
int main() {
    Fixture f;
    auto first=f.Select(); assert(f.recordings==1 && first!=f.cmdBufs[0]);
    assert(f.Select()==first && f.recordings==1 && f.vk_.resets==1);
    // Multiple producer buffers and three swapchain images remain cacheable.
    for(int repeat=0;repeat<4;++repeat) for(uint32_t image=0;image<3;++image) {
        f.imgIdx=image; f.frameDraws[0].img=20+image; f.Select();
    }
    assert(f.recordings==3);
    f.currentFrame=1; f.Select(); assert(f.recordings==4); // Slot isolation.
    ++f.nativeTextureEpoch; f.Select(); assert(f.recordings==5); // Same handle, new lifetime.
    ++f.frameDraws[0].x; f.Select(); assert(f.recordings==6);
    --f.effectiveScissor.extent.width; f.Select(); assert(f.recordings==7);
    f.sx=.5f; f.Select(); assert(f.recordings==8);
    f.nativeGpuQueries=123; f.Select(); assert(f.recordings==9);
    auto timed=f.Select();
    f.nativeTrace=false; assert(f.Select()!=timed && f.recordings==9); // Reuse non-query commands.
    f.nativeTrace=true; assert(f.Select()==timed && f.recordings==9);
    const auto general=[&] { auto n=f.recordings; assert(f.Select()==f.cmdBufs[1]); assert(f.recordings==n+1); };
    f.frameDraws[0].needsTransition=true; general(); f.frameDraws[0].needsTransition=false;
    f.frameDraws[0].upload=5; general(); f.frameDraws[0].upload=0;
    f.frameDraws[0].isAHB=false; general(); f.frameDraws[0].isAHB=true;
    f.hasCurUpload=true; general(); f.hasCurUpload=false;
    f.curVis=true; f.cursorImg=1; f.cursorDS=2;
    auto stillCursor=f.Select(); auto nr=f.recordings;
    assert(f.Select()==stillCursor && f.recordings==nr);
    ++f.ptrX; assert(f.Select()!=stillCursor && f.recordings==nr+1);
    f.curVis=false;
    auto background = f.frameDraws[0]; background.isAHB=false; background.img=70; background.ds=80;
    f.frameDraws.push_back(background);
    auto mixed=f.Select(); nr=f.recordings;
    assert(f.Select()==mixed && f.recordings==nr); // Static CPU background + AHB game.
    ++f.frameDraws[1].x; assert(f.Select()!=mixed && f.recordings==nr+1);
    std::swap(f.frameDraws[0],f.frameDraws[1]);
    auto reordered=f.Select(); assert(reordered!=mixed); // Stacking order is part of the key.
    assert(f.Select()==reordered);
    f.frameDraws[0].upload=5; general(); f.frameDraws[0].upload=0;
    assert(f.Select()==reordered); // Uploaded pixels changed; stable commands still apply.
    f.frameDraws[0].needsTransition=true; general(); f.frameDraws[0].needsTransition=false;
    std::swap(f.frameDraws[0],f.frameDraws[1]); f.frameDraws.pop_back();
    for(int i=1;i<4;++i) { background.img+=1; background.ds+=1; f.frameDraws.push_back(background); }
    auto four=f.Select(); assert(f.Select()==four);
    f.frameDraws.push_back(background); general(); f.frameDraws.resize(1);
    f.filterMode=1; auto nearest=f.Select(); assert(f.Select()==nearest); f.filterMode=0;
    for(auto option : {&f.filterMode,&f.postFXMode,&f.stretchMode}) { *option=2; general(); *option=0; }
    f.useFrameGeneration=true; general(); f.useFrameGeneration=false;
    f.nativeCommandCacheEnabled=false; general(); f.nativeCommandCacheEnabled=true;
    // A failed recording is not published. Failed allocation uses general CB.
    f.frameDraws[0].x=100; f.throwRecord=true;
    try { f.Select(); assert(false); } catch(const std::runtime_error&) {}
    f.throwRecord=false; auto n=f.recordings; f.Select(); assert(f.recordings==n+1);
    f.nativeCommands.Clear([](auto){}); f.vk_.fail=true;
    general(); f.vk_.fail=false;
    // Bounded eviction keeps valid handles, and teardown releases each once.
    for(int x=0;x<40;++x) { f.frameDraws[0].x=x; f.Select(); }
    int released=0; f.nativeCommands.Clear([&](auto){++released;}); assert(released==12);
    f.nativeCommands.Clear([&](auto){++released;}); assert(released==12);
    std::cout << "PASS: production native cache selection, slot/image/AHB keys, invalidation, fallbacks, recording/allocation errors and bounded eviction\n";
}
'''
with tempfile.TemporaryDirectory(prefix='ludashi-command-cache-') as directory:
    unit = pathlib.Path(directory) / 'test.cpp'
    unit.write_text(fixture + selection + '\nreturn selectedCommand;\n}\n};\n' + tests)
    binary = pathlib.Path(directory) / 'test'
    subprocess.run([os.environ.get('CXX','g++'),'-std=c++17','-O1','-g','-fsanitize=address,undefined',
        '-fno-omit-frame-pointer','-DLUDASHI_NATIVE_X11=1','-I'+str(renderer),str(unit),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
