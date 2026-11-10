#pragma once
#include <android/native_window.h>
#include <android/hardware_buffer.h>
struct ARect { int32_t left{}, top{}, right{}, bottom{}; };
struct ASurfaceControl;
struct ASurfaceTransaction;
struct ASurfaceTransactionStats {};
using ASurfaceTransaction_OnComplete = void (*)(void*, ASurfaceTransactionStats*);
enum ASurfaceTransactionVisibility { ASURFACE_TRANSACTION_VISIBILITY_HIDE = 0, ASURFACE_TRANSACTION_VISIBILITY_SHOW = 1 };
enum ASurfaceTransactionTransparency { ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT = 1, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE = 2 };
