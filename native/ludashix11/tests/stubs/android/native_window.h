#pragma once
#include <atomic>
struct ANativeWindow { std::atomic<int> refs{1}; };
inline void ANativeWindow_acquire(ANativeWindow* p) { ++p->refs; }
inline void ANativeWindow_release(ANativeWindow* p) { if (--p->refs == 0) delete p; }
