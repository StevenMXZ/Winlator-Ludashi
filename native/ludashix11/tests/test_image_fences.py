#!/usr/bin/env python3
"""Fence recycling must not make an old image wait for a newer unrelated draw."""
import pathlib, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[3]
directory=root/'app/src/main/cpp/winlator/renderer/vulkan'
source=(directory/'VulkanRendererContext.cpp').read_text()
start=source.index('void VulkanRendererContext::renderFrame()')
checked=source.index('} else if (fenceState!=VK_SUCCESS) throw', start)
forget=source.index('NativeForgetCompletedImageFence(imgInFlight, inFlightFences[currentFrame]);',checked)
acquire=source.index('vk_.AcquireNextImageKHR(',forget)
reset=source.index('vk_.ResetFences(device,1,&inFlightFences[currentFrame])',acquire)
assert checked<forget<acquire<reset
assert '#ifdef LUDASHI_NATIVE_X11' in source[checked:forget]
harness=r'''
#include "native_composition.h"
#include <cassert>
#include <vector>
#include <iostream>
int main() {
    constexpr int f0=1,f1=2;
    std::array<bool,3> pending{false,false,true};
    std::vector<int> old{f0,f1,0}, fixed=old;
    // Slot 0's original draw to image 0 has completed. Slot 1 is still busy.
    NativeForgetCompletedImageFence(fixed,f0);
    assert((fixed==std::vector<int>{0,f1,0}));
    // Recycle the SAME fence handle for a later draw to image 2.
    pending[f0]=true;old[2]=fixed[2]=f0;
    auto waits=[&](int owner){return owner&&pending[owner];};
    assert(waits(old[0])&&!waits(fixed[0])); // Old code waits for unrelated image 2.
    assert(waits(fixed[1])&&waits(fixed[2])); // Actual outstanding owners preserved.
    // Multiple images can refer to one completed submission, including FG outputs.
    pending[f1]=false;fixed.push_back(f1);NativeForgetCompletedImageFence(fixed,f1);
    assert((fixed==std::vector<int>{0,0,f0,0}));
    // Current image and other-slot work stay protected until their fence completes.
    assert(waits(fixed[2]));pending[f0]=false;NativeForgetCompletedImageFence(fixed,f0);
    for(int owner:fixed)assert(owner==0);
    std::array<int,0> empty{};NativeForgetCompletedImageFence(empty,f0);
    NativeForgetCompletedImageFence(fixed,f0);for(int owner:fixed)assert(owner==0);
    std::cout<<"PASS: completed slot fence loses stale image aliases before reuse; pending owners remain protected\n";
}
'''
with tempfile.TemporaryDirectory(prefix='ludashi-image-fences-') as temp:
    folder=pathlib.Path(temp);path=folder/'test.cpp';path.write_text(harness)
    binary=folder/'test'
    subprocess.run(['g++','-std=c++20','-O2','-Wall','-Wextra','-Werror','-I',str(directory),str(path),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
