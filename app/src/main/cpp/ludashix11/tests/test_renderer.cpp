#include "native_screen.h"
#include "VulkanRendererContext.h"
#include <future>
#include <iostream>

int main() {
    using namespace std::chrono_literals;
    {
        auto defaults = xorg::CreateNativeRenderer(1280, 720);
        defaults->Surface(new ANativeWindow);
        if (VulkanRendererContext::instance->validationRequested) return 13;
    }
    auto renderer = xorg::CreateNativeRenderer(1280, 720, true);
    renderer->Surface(new ANativeWindow);
    auto* context = VulkanRendererContext::instance;
    if (!context->validationRequested) return 12;
    xorg::NativeVisualOptions options;
    renderer->VisualOptions(options);
    if (renderer->VisualState().enabled || context->filter || context->postFX || context->stretch) return 21;
    options.filter = 4; options.postFX = 2; options.stretch = 1; options.sharpness = .75f;
    options.frameGen = true; options.frameGenCache = "imported-lossless-cache";
    options.multiplier = 3; options.flowScale = 50; options.refresh = 144.f;
    renderer->VisualOptions(options);
    if (context->filter != 4 || context->postFX != 2 || context->stretch != 1 || context->sharpness != .75f) return 22;
    if (!renderer->VisualState().enabled || context->multiplier != 3 || context->targetRate != 144 || context->flowScale != 50) return 23;
    context->frameGenSupported = false;
    renderer->VisualOptions(options);
    if (renderer->VisualState().enabled || renderer->VisualState().supported || context->frameGenRequested) return 24;
    context->frameGenSupported = true;
    renderer->VisualOptions(options);
    xorg::NativeFrame frame;
    frame.frameGenSourceFrames = 41;
    if (!renderer->Draw(frame)) return 1;
    if (context->sourceFrames != 41) return 25;
    options.frameGen = false;
    // The screen worker drains registered frames before this operation.
    context->fences[0].Signal(); renderer->Drain();
    renderer->VisualOptions(options);
    if (renderer->VisualState().enabled) return 26;
    if (!renderer->Draw(frame)) return 27;
    std::cout << "PASS: native filters, sharpening/stretch, LSFG settings/source counter, unsupported fallback and disable\n";

    // Hold the oldest fence unsignaled while the other slot is submitted.
    // A retirement wait holding pendingMutex would block this Draw indefinitely.
    const unsigned before = VulkanRendererContext::waits;
    VulkanRendererContext::holdWait = true;
    auto retirement = std::async(std::launch::async, [&] { return renderer->WaitCompleted(1); });
    const auto deadline = std::chrono::steady_clock::now() + 1s;
    while (VulkanRendererContext::waits == before && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    if (VulkanRendererContext::waits == before) return 2;
    auto draw = std::async(std::launch::async, [&] { return renderer->Draw(frame); });
    if (draw.wait_for(100ms) != std::future_status::ready || !draw.get()) std::abort();
    if (renderer->ReadyToDraw()) return 3; // Both slots are still retained.
    context->fences[0].Signal(); context->fences[1].Signal();
    auto ready = retirement.get();
    VulkanRendererContext::holdWait = false;
    // Neither path may consume an unregistered second frame.
    if (ready != 1 || !renderer->ReadyToDraw()) return 4;
    if (renderer->PollCompleted(1) != 1) return 5;
    if (renderer->WaitCompleted(0) || renderer->WaitCompleted(1)) return 6;

    // Reset/reuse only the retired slot. A timeout cannot release its resources.
    if (!renderer->Draw(frame) || !renderer->Draw(frame)) return 7;
    if (renderer->WaitCompleted(1) != 0 || renderer->ReadyToDraw()) return 8;
    context->fences[0].Signal(); context->fences[1].Signal();
    if (renderer->Drain() != 2 || !renderer->ReadyToDraw()) return 9;
    frame.commandCacheEnabled = false;
    if (!renderer->Draw(frame) || context->nativeCommandCacheEnabled) return 10;
    context->fences[0].Signal(); context->fences[1].Signal(); renderer->Drain();
    frame.commandCacheEnabled = true;
    if (!renderer->Draw(frame) || !context->nativeCommandCacheEnabled) return 11;
    context->fences[0].Signal(); context->fences[1].Signal(); renderer->Drain();
    std::cout << "PASS: native Vulkan adapter concurrent wait/Draw, registration cap, timeout, slot reuse and drain\n";
    auto imported = [] {
        auto* buffer = new AHardwareBuffer;
        buffer->desc = {16,16,1,5,0,16}; buffer->pixels.resize(16*16*4);
        auto image = std::make_shared<xorg::NativeDrawable>(16,16,32,buffer);
        AHardwareBuffer_release(buffer); return image;
    };
    auto oldImage = imported(), newestImage = imported();
    frame.commandCacheEnabled = false; // Late selection also applies with replay disabled.
    frame.diagnostics = true; // Exercise AHB import accounting in the native adapter.
    frame.entries.push_back({oldImage, nullptr, oldImage->Pixels(), oldImage->Revision(), 0, 0, true});
    context->acquireEntered = false; context->acquireBlocked = true;
    const auto callsBefore = context->boundImages;
    std::atomic<unsigned> latches{};
    const xorg::NativeFrameLatch latch = [&](xorg::NativeFrame& selected) {
        ++latches;
        selected.entries[0] = {newestImage, nullptr, newestImage->Pixels(), newestImage->Revision(), 0,0,true};
    };
    auto acquired = std::async(std::launch::async, [&] { return renderer->Draw(frame, latch); });
    const auto acquireDeadline = std::chrono::steady_clock::now() + 1s;
    while (!context->acquireEntered && std::chrono::steady_clock::now() < acquireDeadline) std::this_thread::yield();
    if (!context->acquireEntered || latches || context->boundImages != callsBefore) std::abort();
    context->acquireBlocked = false;
    if (!acquired.get() || latches != 1 || context->boundImages != std::vector<int64_t>{int64_t(newestImage->ID())}) return 14;
    if (context->texMap.contains(oldImage->ID()) || !context->texMap.at(newestImage->ID()).isAHB) return 16;
    const auto stats = renderer->TakeAHBMetrics();
    if (stats.vkImports != 1 || stats.cacheHits != 0 || stats.cacheEntries != 1) return 20;
    if (context->nativeCommandCacheEnabled || frame.commandCacheEnabled) return 19;
    if (context->nativeFrameReady || context->nativeFrameReadyData) return 15;
    context->fences[0].Signal(); context->fences[1].Signal(); renderer->Drain();
    std::cout << "PASS: native adapter binds newest scene after blocked acquire, retains chosen frame and clears callback\n";
    context->acquireFails = true;
    if (renderer->Draw(frame, latch) || latches != 1 || context->nativeFrameReady || context->nativeFrameReadyData) return 17;
    context->acquireFails = false;
    bool failed = false;
    try {
        renderer->Draw(frame, [](xorg::NativeFrame&) { throw std::runtime_error("mock latch failure"); });
    } catch (const std::runtime_error&) { failed = true; }
    if (!failed || context->nativeFrameReady || context->nativeFrameReadyData) return 18;
    std::cout << "PASS: failed acquire does not latch; failed preparation cannot leave a dangling callback\n";
}

