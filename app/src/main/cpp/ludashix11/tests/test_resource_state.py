#!/usr/bin/env python3
"""Check display atom isolation, resource ranges, and descriptor-zero ownership."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="ludashi-resource-state-") as directory:
    binary = str(Path(directory) / "test")
    subprocess.run([
        os.environ.get("CXX", "c++"), "-std=c++20", "-O1", "-g1",
        "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-fno-pie", "-no-pie",
        "-I" + str(root), str(root / "tests/test_resource_state.cpp"),
        str(root / "core/atom_catalog.cpp"), str(root / "base/owned_descriptor.cpp"), "-o", binary,
    ], check=True)
    subprocess.run([binary], check=True, timeout=20)
print("PASS: isolated atom tables, stable names, resource exhaustion/recycling and descriptor zero")
