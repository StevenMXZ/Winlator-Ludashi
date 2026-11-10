#!/usr/bin/env python3
"""Exercise the production SurfaceControl adapter against a delayed Android API model."""
import os
import pathlib
import subprocess
import tempfile
root = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='ludashi-displayx-renderer-') as directory:
    folder = pathlib.Path(directory)
    args = ['g++', '-std=c++20', '-O1', '-g', '-DFMT_HEADER_ONLY=1', '-include', str(root / 'include/common.h'),
        *['-I' + str(root / p) for p in ['tests/displayx_stubs', 'tests/stubs', '.', 'include', 'third_party']]]
    subprocess.run(args + ['-fPIC', '-shared', str(root / 'tests/test_surface_control_mock.cpp'), '-o', str(folder / 'libandroid.so')], check=True)
    subprocess.run(args + [str(root / 'tests/test_displayx_renderer.cpp'), str(root / 'src/native_displayx_renderer.cpp'),
        str(root / 'src/native_drawable.cpp'), str(root / 'core/drawable.cpp'),
        '-L' + str(folder), '-landroid', '-ldl', '-pthread', '-o', str(folder / 'test')], check=True)
    subprocess.run([str(folder / 'test')], env={**os.environ, 'LD_LIBRARY_PATH': str(folder)}, check=True)
