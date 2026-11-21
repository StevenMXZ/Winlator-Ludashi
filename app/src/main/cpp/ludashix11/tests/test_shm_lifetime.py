#!/usr/bin/env python3
"""Check shared mappings outlive detach and aliases, including descriptor zero."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="ludashi-shm-lifetime-") as directory:
    binary = str(Path(directory) / "test")
    subprocess.run([
        os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g1", "-pthread",
        "-DLUDASHI_HOST_TEST=1", "-fsanitize=address,undefined",
        "-fno-omit-frame-pointer", "-fno-pie", "-no-pie", "-I" + str(root),
        str(root / "tests/test_shm_lifetime.cpp"), str(root / "core/sysv_segments.cpp"),
        str(root / "base/owned_descriptor.cpp"), "-o", binary,
    ], check=True)
    subprocess.run([binary], check=True, timeout=20)
print("PASS: SHM alias cache, failed replacement, in-flight detach lifetime and received descriptor zero")
