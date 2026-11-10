#pragma once
#include "../../stubs/android/native_window.h"
inline int ANativeWindow_getWidth(ANativeWindow*) { return 1280; }
inline int ANativeWindow_getHeight(ANativeWindow*) { return 720; }
inline int ANativeWindow_setBuffersGeometry(ANativeWindow*, int, int, int) { return 0; }
