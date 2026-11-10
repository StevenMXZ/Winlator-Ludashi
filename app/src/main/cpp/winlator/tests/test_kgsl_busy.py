#!/usr/bin/env python3
"""Exercise the real KGSL timestamp decoder with simulated ringbuffers and contexts."""
import pathlib
import subprocess
import tempfile

with tempfile.TemporaryDirectory(prefix='kgsl-busy-') as directory:
    binary = pathlib.Path(directory) / 'test'
    subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Wno-unused-function', '-pthread', '-O1', '-g',
                    '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                    str(pathlib.Path(__file__).with_suffix('.c')), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print('PASS: KGSL ringbuffer/context decoding, context cap and polling-thread stop/join')
