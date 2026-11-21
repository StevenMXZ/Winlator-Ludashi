#!/usr/bin/env python3
"""Compare one million pairwise occlusion queries across 128 mapped sibling windows."""
import argparse
import statistics
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("binary", nargs="+")
parser.add_argument("--runs", type=int, default=7)
args = parser.parse_args()
samples = {binary: [] for binary in args.binary}
for _ in range(args.runs):
    for binary in args.binary:
        result = subprocess.run([binary, "--window-stacking-hotpath"], check=True,
                                capture_output=True, text=True)
        elapsed, checksum = map(int, result.stdout.split())
        assert checksum == 1000000
        samples[binary].append(elapsed / 1e9)
for binary, values in samples.items():
    print(f"{binary}: 1000000 queries, {statistics.median(values):.6f}s (median of {args.runs})")
