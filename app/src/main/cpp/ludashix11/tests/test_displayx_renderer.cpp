#include "native_screen.h"
#include <cassert>
#include <future>
#include <iostream>
extern "C" void TestFinish();
extern "C" int TestControls();
extern "C" int TestReleased();
extern "C" int TestFences();
extern "C" int64_t TestTime();
extern "C" void TestLayer(int, int *);
extern "C" void TestReset();
extern "C" int TestHintCreated();
extern "C" int TestHintClosed();
extern "C" int TestHintReports();
namespace xorg {
std::unique_ptr<NativeRenderer> CreateNativeDisplayXRenderer(u16, u16, const NativeRendererConfig &);
}
int main() {
    using namespace xorg;
    NativeRendererConfig config;
    config.backend = NativeRendererConfig::Backend::DisplayX;
    config.presentMode = 2;
    auto image = std::make_shared<NativeDrawable>(32, 32, 24);
    image->FillRect(0, 0, 32, 32, 0xff112233);
    auto cursor = std::make_shared<NativeDrawable>(4, 4, 32);
    cursor->FillRect(0, 0, 4, 4, 0x8000ff00);
    {
        auto renderer = CreateNativeDisplayXRenderer(640, 480, config);
        renderer->Surface(new ANativeWindow);
        renderer->Options(false, true, 0, 60, 1);
        NativeFrame frame;
        frame.entries.push_back({image, nullptr, image->Pixels(), image->Revision(), 0, 0, true, 1, 32, 32});
        frame.entries.push_back({image, nullptr, image->Pixels(), image->Revision(), 40, 0, true, 2, 32, 32});
        frame.cursor = cursor;
        frame.cursorPixels = cursor->Pixels();
        frame.cursorX = 10;
        frame.cursorY = 10;
        assert(renderer->Draw(frame));
        assert(TestControls() == 4 && TestTime() > 0);
        int layer[7]{};
        TestLayer(0, layer);
        assert(layer[1]);
        TestLayer(1, layer);
        assert(layer[0] == 1 && layer[1] && layer[2] && layer[3] == 160 && layer[4] == 208);
        assert(layer[5] == 2 && uint32_t(layer[6]) == 0xff332211);
        TestLayer(3, layer);
        assert(layer[0] == INT32_MAX && layer[5] == 1);
        TestFinish();
        assert(renderer->Drain() == 1);
        frame.entries.resize(1);
        frame.cursor.reset();
        assert(renderer->Draw(frame));
        TestFinish();
        assert(renderer->Drain() == 1);
        auto *buffer = new AHardwareBuffer;
        buffer->desc = {32, 32, 1, 1, 0, 32};
        buffer->pixels.resize(32 * 32 * 4);
        auto direct = std::make_shared<NativeDrawable>(32, 32, 24, buffer);
        auto external = std::make_shared<NativeExternalFrame>();
        external->drawable = direct;
        int pipefd[2];
        assert(pipe(pipefd) == 0);
        external->fence = pipefd[0];
        close(pipefd[1]);
        int acknowledged{};
        external->completion = [&] { ++acknowledged; };
        frame.entries[0] = {direct, nullptr, nullptr, direct->Revision(), 0, 0, true, 1, 32, 32, external};
        assert(renderer->Draw(frame));
        assert(renderer->Draw(frame));
        assert(renderer->Draw(frame));
        assert(TestFences() == 1 && !renderer->ReadyToDraw() && acknowledged == 0);
        auto retired = std::async(std::launch::async, [&] { return renderer->WaitCompleted(3); });
        TestFinish();
        assert(retired.get() == 3 && acknowledged == 1 && renderer->ReadyToDraw());
        assert(renderer->Draw(frame));
        // Surface loss has to clear buffers before Drain can receive completion.
        renderer->PrepareSurfaceChange();
        assert(renderer->Drain() == 1);
        renderer->Surface(nullptr);
        assert(buffer->refs == 2);
        renderer->Surface(new ANativeWindow);
        assert(renderer->Draw(frame));
        renderer->PrepareSurfaceChange();
        renderer->Drain();
        renderer->Surface(nullptr);
        AHardwareBuffer_release(buffer);
    }
    assert(TestHintCreated() == TestHintClosed() && TestHintCreated() == 2 && TestHintReports() > 0);
    config.performanceMode = false;
    {
        auto renderer = CreateNativeDisplayXRenderer(640, 480, config);
        renderer->Surface(new ANativeWindow);
        NativeFrame frame;
        frame.entries.push_back({image, nullptr, image->Pixels(), image->Revision(), 0, 0, true});
        assert(renderer->Draw(frame) && !renderer->ReadyToDraw());
        TestFinish();
        renderer->Drain();
        assert(TestHintCreated() == 2);
    }
    assert(TestControls() == TestReleased());
    TestReset();
    std::cout << "PASS: DisplayX layers, BGRA CPU conversion/stride, alpha cursor, fit geometry, buffer "
                 "fences, queue limits and surface-loss drain\n";
}
