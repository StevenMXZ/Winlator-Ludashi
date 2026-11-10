#include "native_screen.h"
#include "core/xserver.h"
#include "core/xclient.h"
#include "core/cursor.h"
#include "core/pixmap.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/atom.h"
#include "core/mouse.h"
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
        if (const char* value = std::getenv("LUDASHI_HOST_BLOCK_ACQUIRE_MS")) acquireDelay = std::atoi(value);
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
    bool Draw(NativeFrame& frame, const NativeFrameLatch& latch) override {
        const u32 initialSerial = frame.requests.empty() ? 0 : frame.requests.front()->serial;
        if (acquireDelay && std::ranges::any_of(frame.requests, [](const auto& t) { return t->serial == 41; })) {
            std::cout << "blocked-acquire" << std::endl;
            std::this_thread::sleep_for(std::chrono::milliseconds(acquireDelay));
        }
        if (latch) latch(frame);
        if (traceScene && initialSerial == 41) {
            u32 selectedSerial{};
            for (const auto& entry : frame.entries) if (entry.ticket) selectedSerial = entry.ticket->serial;
            std::cerr << "HostLatch initial_serial=" << initialSerial << " selected_serial=" << selectedSerial
                      << " entries=" << frame.entries.size() << '\n';
        }
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
        if (traceScene) for (const auto& entry : frame.entries) if (entry.pixels && entry.pixels->size() >= 4) {
            u32 first{}; memcpy(&first, entry.pixels->data(), sizeof(first));
            std::cerr << "HostCpu x=" << entry.x << " y=" << entry.y << " first=" << first << '\n';
        }
        if (traceScene && frame.cursor && frame.cursorPixels) {
            const auto& pixels = *frame.cursorPixels;
            const size_t row = size_t(frame.cursor->GetWidth()) * 4;
            u32 first{}, next{}, last{};
            if (pixels.size() >= 4) {
                memcpy(&first, pixels.data(), 4); memcpy(&last, pixels.data() + pixels.size() - 4, 4);
                memcpy(&next, pixels.data() + (pixels.size() > row ? row : 0), 4);
            }
            std::cerr << "HostCursor w=" << frame.cursor->GetWidth() << " h=" << frame.cursor->GetHeight()
                      << " first=" << first << " row1=" << next << " last=" << last << '\n';
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
    int fenceDelay{}, blockDelay{}, acquireDelay{};
    bool traceScene{};
    u32 failSerial{UINT32_MAX};
};
std::unique_ptr<NativeRenderer> CreateNativeRenderer(u16, u16, bool) { return std::make_unique<HostRenderer>(); }
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
    if (std::string(argv[1]) == "--pointer-drag") {
        int sockets[2]; if (socketpair(AF_UNIX, SOCK_STREAM, 0, sockets)) return 2;
        xorg::XClient client(server, sockets[0]);
        auto& wm = server.GetWindowManager();
        const u32 mask = ButtonPressMask | ButtonReleaseMask | PointerMotionMask;
        auto window = wm.CreateWindow(&client, 0x200001, wm.GetRootWindow(), 20, 30, 64, 64,
                                       0, InputOutput, 24, CWEventMask, &mask);
        wm.MapWindow(&client, window.get());
        auto& mouse = server.GetMouse();
        mouse.MoveTo(24, 34); // Enter first; the next move generates MotionNotify.
        mouse.MoveTo(25, 35);
        auto expect = [&](u8 type, u32 target, s16 x, s16 y) {
            xEvent event{};
            timeval timeout{1, 0}; setsockopt(sockets[1], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            if (recv(sockets[1], &event, sizeof(event), MSG_WAITALL) != sizeof(event) ||
                event.u.u.type != type || event.u.keyButtonPointer.event != target ||
                event.u.keyButtonPointer.rootX != x || event.u.keyButtonPointer.rootY != y) {
                std::cerr << "pointer event expected=" << int(type) << " got=" << int(event.u.u.type)
                          << " window=" << event.u.keyButtonPointer.event << " x=" << event.u.keyButtonPointer.rootX
                          << " y=" << event.u.keyButtonPointer.rootY << std::endl;
                std::abort();
            }
        };
        expect(MotionNotify, window->id, 25, 35);
        mouse.SetButton(1, true); expect(ButtonPress, window->id, 25, 35);
        mouse.MoveTo(300, 350); expect(MotionNotify, window->id, 300, 350);
        mouse.SetButton(1, false); expect(ButtonRelease, window->id, 300, 350);
        mouse.MoveTo(400, 450); // Implicit grab must have ended.
        char byte; if (recv(sockets[1], &byte, 1, MSG_DONTWAIT) >= 0) return 3;
        wm.GrabPointer(&client, window.get(), nullptr, nullptr, 0, mask, true, false, false);
        mouse.MoveTo(500, 550); expect(MotionNotify, window->id, 500, 550);
        mouse.SetButton(1, true); expect(ButtonPress, window->id, 500, 550);
        mouse.SetButton(1, false); expect(ButtonRelease, window->id, 500, 550);
        mouse.MoveTo(26, 36); expect(MotionNotify, window->id, 26, 36);
        wm.UngrabPointer(&client, 0);
        close(sockets[1]);
        std::cout << "PASS: implicit drag outside window, release, explicit owner_events fallback and normal routing" << std::endl;
        return 0;
    }
    if (std::string(argv[1]) == "--fence-hotpath") {
        auto lock = server.Lock();
        auto& screen = static_cast<xorg::NativeScreen&>(server.GetScreen());
        auto fence = std::make_shared<xorg::NativeFence>(0x300001, false);
        server.AddResource(fence);
        auto drawable = std::make_shared<xorg::NativeDrawable>(64, 64, 32);
        const auto start = std::chrono::steady_clock::now();
        for (u32 i = 0; i < 50000; ++i) {
            auto ticket = std::make_shared<xorg::PresentTicket>();
            ticket->window = server.GetWindowManager().GetRootWindow()->id;
            ticket->pixmap = 0x300002; ticket->serial = i;
            ticket->drawable = drawable; ticket->idleFence = fence->id;
            fence->triggered = false;
            screen.Present(ticket);
            if (!ticket->complete || !ticket->idle || !fence->triggered) return 3;
        }
        const auto us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count();
        const u64 spurious = server.TakeTestNotifications();
        server.AddSyncWaiter(); screen.FenceSignaled(); server.RemoveSyncWaiter();
        if (server.TakeTestNotifications() != 1) return 4;
        std::cout << "Present hot path: 50000 frames, " << us << " us, " << spurious
                  << " unnecessary eventfd wakes; real waiter wakes immediately" << std::endl;
        return spurious ? 5 : 0;
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



