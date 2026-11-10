#include "native_screen.h"
#include "core/xserver.h"
#include "core/xclient.h"
#include "core/cursor.h"
#include "core/pixmap.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/atom.h"
#include <sys/socket.h>
#include <csignal>
#include <cstdlib>
#include <iostream>
#include <sstream>

namespace xorg {
class HostRenderer final : public NativeRenderer {
public:
    HostRenderer() {
        if (const char* value = std::getenv("LUDASHI_HOST_FENCE_DELAY_MS")) fenceDelay = std::atoi(value);
        if (const char* value = std::getenv("LUDASHI_HOST_BLOCK_DRAW_MS")) blockDelay = std::atoi(value);
        traceScene = std::getenv("LUDASHI_HOST_TRACE_SCENE") != nullptr;
        if (const char* value = std::getenv("LUDASHI_HOST_FAIL_DRAW_SERIAL")) failSerial = std::strtoul(value, nullptr, 10);
    }
    ~HostRenderer() override { if (surface) ANativeWindow_release(surface); }
    void Surface(ANativeWindow* w) override { if (surface) ANativeWindow_release(surface); surface = w; }
    void Options(bool, bool, int, float) override {}
    bool ReadyToDraw() const override { std::lock_guard lock(pendingMutex); return pending.size() < 2; }
    size_t PollCompleted(size_t maxReady) override {
        std::lock_guard lock(pendingMutex);
        size_t ready{};
        while (ready < maxReady && !pending.empty() && pending.front() <= std::chrono::steady_clock::now()) {
            pending.pop_front(); ++ready;
        }
        return ready;
    }
    size_t WaitCompleted(size_t maxReady) override {
        std::chrono::steady_clock::time_point deadline;
        {
            std::lock_guard lock(pendingMutex);
            if (!maxReady || pending.empty()) return 0;
            deadline = pending.front();
        }
        std::this_thread::sleep_until(std::min(deadline,
            std::chrono::steady_clock::now() + std::chrono::milliseconds(10)));
        return PollCompleted(maxReady);
    }
    size_t Drain() override {
        size_t ready{};
        for (;;) {
            ready += PollCompleted(SIZE_MAX);
            { std::lock_guard lock(pendingMutex); if (pending.empty()) return ready; }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }
    bool Draw(const NativeFrame& frame) override {
        std::vector<sp<PresentTicket>> sampled;
        for (auto& entry : frame.entries) {
            if (entry.ticket) sampled.push_back(entry.ticket);
        }
        if (std::ranges::any_of(frame.requests, [&](const auto& t) { return t->serial == failSerial; })) {
            std::cout << "failed-draw" << std::endl;
            throw std::runtime_error("Simulated host Draw failure");
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
        if (traceScene) for (const auto& entry : frame.entries) if (entry.ticket) {
            std::ostringstream trace;
            trace << "HostScene serial=" << entry.ticket->serial << " entries=" << frame.entries.size()
                      << " pixmap=" << entry.ticket->pixmap << " x=" << entry.x << " y=" << entry.y
                      << " w=" << entry.drawable->GetWidth() << " h=" << entry.drawable->GetHeight()
                      << " cursor=" << (frame.cursor ? frame.cursor->ID() : 0)
                      << " cursor_x=" << frame.cursorX << " cursor_y=" << frame.cursorY << '\n';
            std::cerr << trace.str();
        }
        {
            std::lock_guard lock(pendingMutex);
            pending.push_back(std::chrono::steady_clock::now() +
                             std::chrono::milliseconds(sampled.empty() ? 0 : fenceDelay));
        }
        // Model a GPU fence followed by blocking FIFO presentation. Producer
        // notifications must already have been sent independently of both.
        if (blockDelay && std::ranges::any_of(frame.requests, [](const auto& t) { return t->serial == 11; })) {
            std::cout << "blocked-draw" << std::endl;
            std::this_thread::sleep_for(std::chrono::milliseconds(blockDelay));
            for (auto& ticket : sampled)
                if (!ticket->drawable || (!ticket->drawable->Pixels() && !ticket->drawable->HardwareBuffer())) std::abort();
        }
        return true;
    }
    ANativeWindow* surface{};
    mutable std::mutex pendingMutex;
    std::deque<std::chrono::steady_clock::time_point> pending;
    int fenceDelay{}, blockDelay{};
    bool traceScene{};
    u32 failSerial{UINT32_MAX};
};
std::unique_ptr<NativeRenderer> CreateNativeRenderer(u16, u16) { return std::make_unique<HostRenderer>(); }
}
static volatile sig_atomic_t quit;
int main(int argc, char** argv) {
    if (argc != 2 && argc != 3) return 2;
    signal(SIGTERM, [](int) { quit = 1; }); signal(SIGINT, [](int) { quit = 1; });
    xorg::XServer server(nullptr, xorg::ScreenInfo(1280, 720, 4));
    if (std::string(argv[1]) == "--cursor-hud") {
        auto& screen = static_cast<xorg::NativeScreen&>(server.GetScreen());
        xorg::Pixmap source(screen, 101, 3, 1, u8(1)), mask(screen, 102, 3, 1, u8(1));
        const u8 src[] = {3,0,0,0}, msk[] = {5,0,0,0};
        source.GetDrawable()->WriteBinaryPixels(src, 3, 1, 1, 0);
        mask.GetDrawable()->WriteBinaryPixels(msk, 3, 1, 1, 0);
        xorg::Cursor cursor(screen, 103, 1, 0, &source, &mask, 0x123456, 0xabcdef);
        u32 pixels[3]{};
        cursor.GetDrawable()->ReadPixels(reinterpret_cast<u8*>(pixels), 0, 0, 3, 1, 3);
        if (pixels[0] != 0xff123456 || pixels[1] != 0 || pixels[2] != 0xffabcdef || cursor.GetHotspotX() != 1) return 3;
        cursor.Recolor(0x908070, 0x102030);
        cursor.GetDrawable()->ReadPixels(reinterpret_cast<u8*>(pixels), 0, 0, 3, 1, 3);
        if (pixels[0] != 0xff908070 || pixels[1] != 0 || pixels[2] != 0xff102030) return 4;
        xorg::Cursor opaque(screen, 104, 0, 0, &source, nullptr, 0x123456, 0xabcdef);
        opaque.GetDrawable()->ReadPixels(reinterpret_cast<u8*>(pixels), 0, 0, 3, 1, 3);
        if (pixels[0] != 0xff123456 || pixels[1] != 0xff123456 || pixels[2] != 0xffabcdef) return 5;
        // The HUD counts producer frames, including more than one per compositor draw.
        auto lock = server.Lock();
        auto* root = server.GetWindowManager().GetRootWindow();
        auto makeWindow = [&](u32 id, xorg::Window* parent) {
            auto w = std::make_shared<xorg::Window>(server.GetWindowManager(), id, parent, false, 0,0,64,64,24,0,nullptr);
            w->GetAttributes().mapped = true; server.AddResource(w); return w;
        };
        auto game = makeWindow(201, root), child = makeWindow(202, game.get()), desktop = makeWindow(203, root);
        auto atom = xorg::Atom::Intern("_MESA_DRV_ENGINE_NAME");
        game->ModifyProperty(atom, 31, 8, 0, {'D','X','V','K'});
        screen.HudFrame(child->id);
        if (screen.HudFrames() || screen.HudWindow()) return 6;
        screen.SetHudEnabled(true);
        for (int i = 0; i < 100; ++i) screen.HudFrame(child->id);
        screen.HudFrame(desktop->id);
        if (screen.HudFrames() != 100 || screen.HudWindow() != game->id) return 7;
        screen.SetHudEnabled(false); screen.HudFrame(child->id);
        if (screen.HudFrames() || screen.HudWindow()) return 8;
        std::cout << "cursor mask/colors/recolor/hotspot and optional producer HUD passed" << std::endl;
        // These standalone resource fixtures are not attached to the managed tree.
        game->GetAttributes().mapped = child->GetAttributes().mapped = desktop->GetAttributes().mapped = false;
        return 0;
    }
    if (std::string(argv[1]) == "--disconnected-output") {
        int pair[2];
        if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) < 0) return 2;
        xorg::XClient client(server, pair[0]);
        close(pair[1]);
        xEvent event{};
        // Input and scene notifications use this same path outside RunLoop's catch.
        client.SendEvent(event);
        client.SendEvent(event);
        std::cout << "disconnected output survived" << std::endl;
        return 0;
    }
    server.UpdateSurface(new ANativeWindow);
    if (const char* hidden = std::getenv("LUDASHI_HOST_HIDDEN_WM_CLASS"); hidden && *hidden)
        static_cast<xorg::NativeScreen&>(server.GetScreen()).SetUnviewableWMClasses({hidden});
    if (const char* rates = std::getenv("LUDASHI_HOST_RANDR_RATES")) {
        std::vector<u16> values; std::stringstream list(rates); std::string value;
        while (std::getline(list, value, ',')) values.push_back(std::stoi(value));
        static_cast<xorg::NativeScreen&>(server.GetScreen()).SetRandrRates(std::move(values));
    }
    if (const char* diagnostics = std::getenv("WINLATOR_X11"))
        static_cast<xorg::NativeScreen&>(server.GetScreen()).SetDiagnostics(std::string(diagnostics) == "1");
    if (const char* limit = std::getenv("LUDASHI_HOST_FPS_LIMIT"))
        static_cast<xorg::NativeScreen&>(server.GetScreen()).SetOptions(false, true, std::atoi(limit), 60.f);
    server.StartTest(std::stoi(argv[1]), argc == 3 ? std::stoi(argv[2]) : -1);
    std::cout << "ready" << std::endl;
    while (!quit) std::this_thread::sleep_for(std::chrono::milliseconds(10));
}


