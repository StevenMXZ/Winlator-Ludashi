#include "native_screen.h"
#include "native_displayx_visibility.h"
#include <cassert>
#include <future>
#include <iostream>
extern "C" void TestFinish();
extern "C" int TestProperties();
extern "C" int TestDataSpace(int);
extern "C" int TestHdrCalls(int);
extern "C" int TestTransactions();
extern "C" void TestBufferDesc(int, AHardwareBuffer_Desc *);
extern "C" int TestControls();
extern "C" int TestReleased();
extern "C" int TestFences();
extern "C" int64_t TestTime();
extern "C" int64_t TestFrameTimeline();
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
        NativeEntry lower{image, nullptr, image->Pixels(), image->Revision(), 10, 20, true, 1, 64, 64};
        NativeEntry upper = lower;
        upper.window = 2;
        std::vector<NativeEntry> entries{lower, upper};
        CullCoveredDisplayXLayers(entries);
        assert(entries.size() == 1 && entries[0].window == 2);
        // Window destination dimensions, not the 32x32 backing image, define coverage.
        upper.windowWidth = upper.windowHeight = 32;
        entries = {lower, upper}; CullCoveredDisplayXLayers(entries);
        assert(entries.size() == 2);
        upper.windowWidth = upper.windowHeight = 64;
        upper.opaque = false;
        entries = {lower, upper}; CullCoveredDisplayXLayers(entries);
        assert(entries.size() == 2);
        upper.opaque = true; upper.x += 1;
        entries = {lower, upper}; CullCoveredDisplayXLayers(entries);
        assert(entries.size() == 2);
        upper.x = 10;
        int completed = 0;
        lower.external = std::make_shared<NativeExternalFrame>();
        lower.external->completion = [&] { ++completed; };
        entries = {lower, upper}; CullCoveredDisplayXLayers(entries);
        assert(entries.size() == 1 && completed == 1);
        entries = {upper, lower}; CullCoveredDisplayXLayers(entries);
        assert(entries.size() == 1 && entries[0].window == 1); // Topmost ordering stays intact.
        entries.clear(); lower.external.reset();
    }
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
        const int properties = TestProperties(), allocations = testAllocations;
        const int transactions = TestTransactions();
        assert(renderer->Draw(frame));
        TestFinish(); renderer->Drain();
        assert(TestProperties() == properties && testAllocations == allocations);
        assert(TestTransactions() == transactions);
        frame.cursorX += 2;
        assert(renderer->Draw(frame));
        TestFinish(); renderer->Drain();
        assert(TestProperties() == properties + 1 && testAllocations == allocations);
        AHardwareBuffer_Desc description{};
        TestBufferDesc(1, &description);
        assert(description.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
        assert(description.usage & AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY);
        TestBufferDesc(3, &description);
        assert(description.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
        frame.entries[0].x = 20;
        auto invalid = std::make_shared<NativeDrawable>(32, 32, 24);
        frame.entries[1].drawable = invalid;
        frame.entries[1].pixels.reset();
        bool failed = false;
        try { renderer->Draw(frame); } catch (const std::runtime_error&) { failed = true; }
        assert(failed);
        frame.entries[1].pixels = invalid->Pixels();
        assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
        TestLayer(1, layer);
        assert(layer[3] == 190); // Failed transactions must replay cached geometry.
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
        external->colorSpace = 1000104008;
        external->hasHdr = true;
        external->hdr = {.64f, .33f, .3f, .6f, .15f, .06f, .3127f, .329f, 1000.f, .01f, 1000.f, 400.f};
        int pipefd[2];
        assert(pipe(pipefd) == 0);
        external->fence = pipefd[0];
        close(pipefd[1]);
        int acknowledged{};
        external->completion = [&] { ++acknowledged; };
        frame.entries[0] = {direct, nullptr, nullptr, direct->Revision(), 0, 0, true, 1, 32, 32, external};
        const int references = buffer->refs;
        frame.frameTimeline = 123;
        assert(renderer->Draw(frame));
        assert(TestFrameTimeline() == 123);
        assert(TestDataSpace(1) == 163971072 && TestHdrCalls(1) == 2);
        assert(buffer->refs == references + 1); // SurfaceControl only; no extra AHB wrapper.
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
    // Neither fake nor true DisplayX should add an ANativeWindow backing layer.
    assert(testNativeWindowLocks == 0 && testNativeWindowPosts == 0);
    TestReset();
    // Allocators on older devices can reject overlay usage and/or RGBX.
    for (int rejectRGBX = 0; rejectRGBX < 2; ++rejectRGBX) {
        setenv("TEST_REJECT_OVERLAY", "1", 1);
        if (rejectRGBX) setenv("TEST_REJECT_RGBX", "1", 1);
        {
            auto renderer = CreateNativeDisplayXRenderer(640, 480, config);
            renderer->Surface(new ANativeWindow);
            NativeFrame frame;
            frame.entries.push_back({image, nullptr, image->Pixels(), image->Revision(), 0, 0, true, 1});
            assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
            AHardwareBuffer_Desc description{};
            TestBufferDesc(1, &description);
            assert(!(description.usage & AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY));
            assert(description.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
            const int failures = testAllocationFailures;
            image->FillRect(0, 0, 1, 1, 0xff123456);
            frame.entries[0].pixels = image->Pixels();
            frame.entries[0].revision = image->Revision();
            assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
            assert(testAllocationFailures == failures);
            const int allocations = testAllocations;
            frame.entries[0].x = -1000;
            image->FillRect(0, 0, 1, 1, 0xff654321);
            frame.entries[0].pixels = image->Pixels();
            frame.entries[0].revision = image->Revision();
            assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
            assert(testAllocations == allocations);
            frame.entries[0].x = 0;
            frame.entries[0].opaque = false;
            assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
            int layer[7]{}; TestLayer(1, layer);
            assert(layer[1] && layer[5] == 1);
            TestBufferDesc(1, &description);
            assert(description.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
        }
        TestReset();
    }
    {
        const int controls = TestControls();
        auto renderer = CreateNativeDisplayXRenderer(640, 480, config);
        renderer->Surface(new ANativeWindow);
        NativeFrame frame;
        frame.entries.push_back({image, nullptr, image->Pixels(), image->Revision(), 0, 0, true, 1});
        frame.cursor = std::make_shared<NativeDrawable>(4, 4, 32);
        frame.cursorPixels = frame.cursor->Pixels();
        assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
        assert(TestControls() == controls + 2); // Root + game; no invisible cursor plane.
        frame.cursor->FillRect(0, 0, 4, 4, 0x800000ff);
        frame.cursorPixels = frame.cursor->Pixels();
        assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
        assert(TestControls() == controls + 3);
        const int released = TestReleased();
        frame.cursor->FillRect(0, 0, 4, 4, 0);
        frame.cursorPixels = frame.cursor->Pixels();
        assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
        assert(TestReleased() == released + 1);
        frame.cursor->FillRect(0, 0, 1, 1, 0xff123456);
        frame.cursorPixels = frame.cursor->Pixels();
        assert(renderer->Draw(frame)); TestFinish(); renderer->Drain();
        assert(TestControls() == controls + 4);
    }
    TestReset();
    unsetenv("TEST_REJECT_OVERLAY"); unsetenv("TEST_REJECT_RGBX");
    assert(testNativeWindowLocks == 0 && testNativeWindowPosts == 0);
    std::cout << "PASS: DisplayX layers, BGRA CPU conversion/stride, alpha cursor, fit geometry, buffer "
                 "fences, queue limits and surface-loss drain\n";
}
