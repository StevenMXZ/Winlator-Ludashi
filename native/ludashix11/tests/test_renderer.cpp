#include "native_screen.h"
#include "VulkanRendererContext.h"
#include <future>
#include <iostream>

int main() {
    using namespace std::chrono_literals;
    auto renderer = xorg::CreateNativeRenderer(1280, 720);
    renderer->Surface(new ANativeWindow);
    auto* context = VulkanRendererContext::instance;
    xorg::NativeFrame frame;
    if (!renderer->Draw(frame)) return 1;

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
    std::cout << "PASS: native Vulkan adapter concurrent wait/Draw, registration cap, timeout, slot reuse and drain\n";
}
