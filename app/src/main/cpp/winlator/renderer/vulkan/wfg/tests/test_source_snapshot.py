#!/usr/bin/env python3
"""A source counter must not become visible before its matching AHB content."""
from pathlib import Path
import os
import subprocess
import tempfile


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    root = Path(__file__).resolve().parents[2]
    source = (root / 'VulkanRendererContext.cpp').read_text()
    methods = '\n'.join(method(source, signature) for signature in [
        'void VulkanRendererContext::setFrameGenerationSourceCount(',
        'void VulkanRendererContext::updateWindowContentAHB('])
    harness = r'''
#include "context_public.h"
#include <cassert>
#include <inttypes.h>
#include <iostream>
VulkanRendererContext::VulkanRendererContext(ANativeWindow*, int, int, void*, bool) {}
VulkanRendererContext::~VulkanRendererContext() {}
bool VulkanRendererContext::importAHBToWinTex(WinTex&, AHardwareBuffer*) { return false; }
void VulkanRendererContext::destroyWinTex(WinTex&) { std::abort(); }
''' + methods + r'''
int main() {
    VulkanRendererContext ctx(nullptr, 0, 0, nullptr, false);
    ctx.frameGenRequested = true; ctx.frameGenEngine = 1;
    auto* ahb = reinterpret_cast<AHardwareBuffer*>(1);
    auto& cached = ctx.ahbImportCache[ahb];
    cached.img = (VkImage)2; cached.w = 1280; cached.h = 720;
    ctx.frameGenSourceFrames.store(40);
    ctx.setFrameGenerationSourceCount(41);
    assert(ctx.frameGenSourceFrames.load() == 40); // cursor wake before content
    assert(ctx.wfgPendingSourceFrames.load() == 41);
    ctx.updateWindowContentAHB(8, ahb, 1280, 720, 0, 0);
    assert(ctx.frameGenSourceFrames.load() == 41);
    assert(ctx.texMap[8].img == cached.img);
    // A failed import does not publish an image that was never installed.
    ctx.setFrameGenerationSourceCount(42);
    ctx.updateWindowContentAHB(8, reinterpret_cast<AHardwareBuffer*>(3), 1280, 720, 0, 0);
    assert(ctx.frameGenSourceFrames.load() == 41);
    ctx.updateWindowContentAHB(8, ahb, 1280, 720, 0, 0);
    assert(ctx.frameGenSourceFrames.load() == 42);
    // The LSFG counter retains its original immediate-publish semantics.
    ctx.frameGenEngine = 0;
    ctx.setFrameGenerationSourceCount(43);
    assert(ctx.frameGenSourceFrames.load() == 43);
    std::cout << "PASS: WFG source count paired with installed AHB, failed-import retry and unchanged LSFG source publication\n";
}
'''
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        (temp / 'android').mkdir()
        (temp / 'android/hardware_buffer.h').write_text(
            'struct AHardwareBuffer; static inline void AHardwareBuffer_acquire(AHardwareBuffer*) {}\n')
        (temp / 'android/native_window.h').write_text('struct ANativeWindow;\n')
        (temp / 'jni.h').write_text('')
        (temp / 'android/log.h').write_text(
            '#define ANDROID_LOG_DEBUG 3\n#define ANDROID_LOG_ERROR 6\n'
            'static inline int __android_log_print(int,const char*,const char*,...) {return 0;}\n')
        (temp / 'context_public.h').write_text(
            (root / 'VulkanRendererContext.h').read_text().replace('private:', 'public:'))
        code, binary = temp / 'snapshot.cpp', temp / 'snapshot'
        code.write_text(harness)
        command = [os.environ.get('CXX', 'c++'), '-std=c++17', '-O2', '-pthread',
                   '-I', str(temp), '-I', str(root)]
        if os.environ.get('VULKAN_HEADERS'):
            command += ['-I', os.environ['VULKAN_HEADERS']]
        subprocess.run(command + [str(code), '-o', str(binary)], check=True, timeout=60)
        subprocess.run([str(binary)], check=True, timeout=30)


if __name__ == '__main__':
    main()
