#!/usr/bin/env python3
"""Compile/link the EGL/DisplayX adapters, native core and real JNI for Android ARM64.

Pass an Android NDK path (or set ANDROID_NDK_HOME). This deliberately excludes the
unchanged Vulkan adapter and its generated shaders; the normal app build covers it.
"""
import os
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
ndk = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else os.environ['ANDROID_NDK_HOME']).resolve()
cmake = os.environ.get('CMAKE', 'cmake')
with tempfile.TemporaryDirectory(prefix='ludashi-android-backends-') as directory:
    folder = pathlib.Path(directory)
    (folder / 'CMakeLists.txt').write_text('''
cmake_minimum_required(VERSION 3.22)
project(XppBackendLinkTest LANGUAGES C CXX)
set(ROOT "''' + str(root) + '''")
file(GLOB CORE ${ROOT}/core/*.cpp ${ROOT}/core/io/*.cpp ${ROOT}/core/cursor/*.cpp ${ROOT}/base/*.cpp)
file(GLOB NATIVE ${ROOT}/src/native_*.cpp)
list(FILTER NATIVE EXCLUDE REGEX "native_renderer\\\\.cpp$")
add_library(xpp_backend_test SHARED ${CORE} ${NATIVE} factory.cpp)
target_compile_features(xpp_backend_test PRIVATE cxx_std_20)
target_compile_definitions(xpp_backend_test PRIVATE FMT_HEADER_ONLY=1)
target_compile_options(xpp_backend_test PRIVATE -include ${ROOT}/include/common.h)
target_include_directories(xpp_backend_test PRIVATE ${ROOT} ${ROOT}/include ${ROOT}/third_party)
target_link_libraries(xpp_backend_test PRIVATE android log EGL GLESv2 atomic)
target_link_options(xpp_backend_test PRIVATE -Wl,--no-undefined)
''')
    (folder / 'factory.cpp').write_text('''
#include "native_screen.h"
namespace xorg {
std::unique_ptr<NativeRenderer> CreateNativeEGLRenderer(u16, u16, const NativeRendererConfig&);
std::unique_ptr<NativeRenderer> CreateNativeDisplayXRenderer(u16, u16, const NativeRendererConfig&);
std::unique_ptr<NativeRenderer> CreateNativeRenderer(u16 w, u16 h, bool, const NativeRendererConfig& c) {
    if (c.backend == NativeRendererConfig::Backend::EGL) return CreateNativeEGLRenderer(w, h, c);
    if (c.backend == NativeRendererConfig::Backend::DisplayX) return CreateNativeDisplayXRenderer(w, h, c);
    throw std::runtime_error("This link test excludes the Vulkan adapter");
}
}
''')
    for command in ([cmake, '-S', str(folder), '-B', str(folder / 'build'),
            '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
            '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-28'],
            [cmake, '--build', str(folder / 'build'), '-j4']):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if result.returncode:
            print(result.stdout)
            result.check_returncode()
print('PASS: Android ARM64 native core, EGL/DisplayX adapters and JNI link without undefined symbols')
