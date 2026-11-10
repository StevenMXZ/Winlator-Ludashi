#pragma once
#include <string>

namespace xorg {
// Immutable startup options, initialized before the screen worker is created.
struct NativeRendererConfig {
    enum class Backend { Vulkan, EGL, DisplayX };
    Backend backend = Backend::Vulkan;
    bool trueDisplayX{}, performanceMode{true}, presentAtRefreshRate{true}, precisePresentation{};
    std::string driverPath, libraryName, nativeLibDir;
    bool swapRB = false;
    int presentMode = 2; // VK_PRESENT_MODE_FIFO_KHR
};
}
