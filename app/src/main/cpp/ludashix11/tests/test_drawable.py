#!/usr/bin/env python3
import os
import pathlib
import subprocess
import tempfile
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='ludashi-drawable-') as directory:
    binary=pathlib.Path(directory)/'test'
    subprocess.run([os.environ.get('CXX','g++'),'-std=c++20','-O1','-g','-fsanitize=address,undefined',
        '-fno-omit-frame-pointer','-DFMT_HEADER_ONLY=1','-include',str(root/'include/common.h'),
        *['-I'+str(root/p) for p in ['.','include','third_party','tests/stubs']],
        str(root/'tests/test_drawable.cpp'),str(root/'src/native_drawable.cpp'),str(root/'core/drawable.cpp'),
        '-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
