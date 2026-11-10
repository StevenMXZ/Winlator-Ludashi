#!/usr/bin/env python3
import os
import pathlib
import subprocess
import tempfile

def run(root):
    root = pathlib.Path(root)
    with tempfile.TemporaryDirectory(prefix='fakeinput-wait-') as directory:
        temp = pathlib.Path(directory)
        # glibc uses an unsigned-long ioctl request; Bionic's existing shim uses int.
        source = (root / 'fakeinput.cpp').read_text()
        source = source.replace('EXPORT int ioctl(int fd, int op, ...)',
                                'EXPORT int ioctl(int fd, unsigned long op, ...)')
        (temp / 'fakeinput.cpp').write_text(source)
        flags = ['g++', '-std=c++20', '-O1', '-g', '-fsanitize=address,undefined',
                 '-fno-omit-frame-pointer', '-pthread', '-I', str(root)]
        lib = temp / 'libfakeinput.so'
        test = temp / 'test'
        subprocess.run(flags + ['-shared', '-fPIC', '-fvisibility=hidden', str(temp/'fakeinput.cpp'), '-ldl', '-o', str(lib)], check=True)
        subprocess.run(flags + [str(root/'tests/test_fakeinput_wait.cpp'), '-ldl', '-o', str(test)], check=True)
        env = dict(os.environ, FAKE_EVDEV_DIR=str(temp), FAKE_EVDEV_LOG='0')
        subprocess.run([str(test), str(lib)], env=env, check=True, timeout=30)
        reuse = temp / 'test-reuse'
        subprocess.run(flags + [str(root/'tests/test_fakeinput_wait_reuse.cpp'), '-o', str(reuse)], check=True)
        subprocess.run([str(reuse)], env=env, check=True, timeout=5)

if __name__ == '__main__':
    run(pathlib.Path(__file__).resolve().parents[1])
