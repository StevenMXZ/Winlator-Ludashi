#!/usr/bin/env python3
"""Run the actual DisplayX queue against dropped windows and owned fence FDs."""
import os
import pathlib
import subprocess
import tempfile

source = pathlib.Path(__file__).with_suffix('.cpp')
with tempfile.TemporaryDirectory(prefix='displayx-queue-') as directory:
    binary = pathlib.Path(directory) / 'test'
    subprocess.run([os.environ.get('CXX', 'g++'), '-std=c++17', '-O1', '-g',
                    '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                    str(source), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print('PASS: mailbox coalescing, FIFO order, wire acknowledgements, window/image retirement and FD ownership')
