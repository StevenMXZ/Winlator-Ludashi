#!/usr/bin/env python3
"""Render the production EGL compositor with Mesa; pass an Android NDK for Khronos headers."""
import os
import pathlib
import subprocess
import sys
import tempfile
root = pathlib.Path(__file__).resolve().parents[1]
ndk = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else os.environ['ANDROID_NDK_HOME'])
headers = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
with tempfile.TemporaryDirectory(prefix='ludashi-egl-') as directory:
    folder = pathlib.Path(directory)
    for name in ('EGL', 'GLES2', 'GLES3', 'KHR'):
        (folder / name).symlink_to(headers / name, target_is_directory=True)
    binary = folder / 'test'
    subprocess.run(['g++', '-std=c++20', '-O1', '-g', '-DFMT_HEADER_ONLY=1', '-DEGL_NO_PLATFORM_SPECIFIC_TYPES',
        '-include', str(root / 'include/common.h'), '-I' + str(folder),
        *['-I' + str(root / p) for p in ['tests/egl_stubs', 'tests/stubs', '.', 'include', 'third_party']],
        str(root / 'tests/test_egl_renderer.cpp'), str(root / 'src/native_egl_renderer.cpp'),
        str(root / 'src/native_drawable.cpp'), str(root / 'core/drawable.cpp'),
        '-l:libEGL.so.1', '-l:libGL.so.1', '-ldl', '-pthread', '-o', str(binary)], check=True)
    subprocess.run([str(binary)], env={**os.environ, 'EGL_PLATFORM': 'surfaceless', 'LIBGL_ALWAYS_SOFTWARE': '1'}, check=True)
