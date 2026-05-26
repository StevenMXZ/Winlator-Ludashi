#!/bin/bash
# Build the AHB Direct Compositing Vulkan Layer for ARM64 and x86_64
#
# Prerequisites: Linux NDK at ~/android-ndk-r27c
# Run from: dlls/vulkan_layer/

set -e

NDK="$HOME/android-ndk-r27c"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
CC_ARM64="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
CC_X86_64="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang"

CFLAGS="-shared -fPIC -DANDROID -DVK_USE_PLATFORM_ANDROID_KHR -O2 -Wall -Wno-unused-function"
LDFLAGS="-llog -landroid -Wl,--allow-shlib-undefined"

echo "=== Building AHB Implicit Layer for ARM64 ==="
$CC_ARM64 $CFLAGS --sysroot="$SYSROOT" \
    -o libahb_layer.arm64.so \
    ahb_layer.c ../wineandroid.drv/vulkan_ahb.c \
    $LDFLAGS -ldl

echo "=== Building AHB Implicit Layer for x86_64 ==="
$CC_X86_64 $CFLAGS --sysroot="$SYSROOT" \
    -o libahb_layer.x86_64.so \
    ahb_layer.c ../wineandroid.drv/vulkan_ahb.c \
    $LDFLAGS -ldl

echo ""
echo "=== Build complete ==="
file libahb_layer.arm64.so
file libahb_layer.x86_64.so
echo ""
echo "Deploy Implicit Layer:"
echo "  adb push libahb_layer.arm64.so /data/local/tmp/libahb_layer.so"
echo "  adb shell 'run-as com.winlator.cmod cp /data/local/tmp/libahb_layer.so files/imagefs/usr/lib/libahb_layer.so'"
echo "  adb push ahb_layer.json /data/local/tmp/ahb_layer.json"
echo "  adb shell 'run-as com.winlator.cmod mkdir -p files/imagefs/usr/share/vulkan/implicit_layer.d'"
echo "  adb shell 'run-as com.winlator.cmod cp /data/local/tmp/ahb_layer.json files/imagefs/usr/share/vulkan/implicit_layer.d/ahb_layer.json'"
