#pragma once
#include "../../egl_stubs/android/native_window.h"
struct ANativeWindow_Buffer { int width{}, height{}, stride{}, format{}; void* bits{}; };
inline int ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer* buffer, void*) {
    static thread_local std::vector<uint32_t> pixels(1280 * 720);
    *buffer = {1280, 720, 1280, 1, pixels.data()}; return 0;
}
inline int ANativeWindow_unlockAndPost(ANativeWindow*) { return 0; }
