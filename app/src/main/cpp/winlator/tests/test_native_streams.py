#!/usr/bin/env python3
"""Exercise actual native streams/epoll with a JNI callback harness and sanitizers."""
import os
import pathlib
import shutil
import subprocess
import tempfile

source = pathlib.Path(__file__).with_suffix('.c')
java = pathlib.Path(os.environ.get('JAVA_HOME') or pathlib.Path(shutil.which('javac')).resolve().parents[1])
with tempfile.TemporaryDirectory(prefix='winlator-native-streams-') as directory:
    binary = pathlib.Path(directory) / 'test'
    subprocess.run([os.environ.get('CC', 'gcc'), '-std=c11', '-O1', '-g', '-pthread',
                    '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                    '-I' + str(java / 'include'), '-I' + str(java / 'include/linux'),
                    '-I' + str(source.parents[1] / 'include'),
                    str(source), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=40)
