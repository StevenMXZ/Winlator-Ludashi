#!/usr/bin/env python3
"""Compare PolyRectangle with equivalent thin PolyLine outlines over real X11 sockets."""
import argparse
import socket
import statistics
import struct
import subprocess
import tempfile
import time
from test_wire import Client


def sample(binary, opcode, count):
    channel, child = socket.socketpair()
    with channel, child, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen([binary, str(child.fileno())], pass_fds=(child.fileno(),),
                                   stdout=subprocess.PIPE, stderr=log, text=True)
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            c = Client(channel)
            with c.sock:
                image, gc = c.base | 1, c.base | 2
                c.send(53, 24, struct.pack("<IIHH", image, c.root, 128, 128))
                c.send(55, payload=struct.pack("<IIII", gc, image, 4, 0x123456))
                c.barrier()
                if opcode == 67:
                    payload = struct.pack("<IIhhHH", image, gc, 10, 10, 100, 90)
                else:
                    payload = struct.pack("<II10h", image, gc, 10, 10, 110, 10, 110, 100, 10, 100, 10, 10)
                packet = struct.pack("<BBH", opcode, 0, (len(payload) + 4) // 4) + payload
                start = time.perf_counter()
                c.sock.sendall(packet * count)
                c.seq += count
                c.barrier()
                elapsed = time.perf_counter() - start
                reply = c.reply(c.send(73, 2, struct.pack("<IhhHHI", image, 0, 0, 128, 128, 0xffffffff)))
                return elapsed, reply[32:]
        finally:
            process.terminate(); process.wait(10)
            log.seek(0); output = log.read()
            assert process.returncode == 0, output


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("binary")
parser.add_argument("--baseline", help="Compare PolyLine on the baseline and current builds")
parser.add_argument("--runs", type=int, default=7)
parser.add_argument("--count", type=int, default=5000)
args = parser.parse_args()
if not 1 <= args.count <= 60000: parser.error("--count must be 1..60000 to avoid sequence wrap")
cases = [(args.binary, 65, "PolyLine outline"), (args.binary, 67, "PolyRectangle")]
if args.baseline:
    cases = [(args.baseline, 65, "Baseline PolyLine"), (args.binary, 65, "Current PolyLine")]
samples = {label: [] for _, _, label in cases}
reference = None
for run in range(args.runs):
    for binary, opcode, label in (cases if run % 2 == 0 else reversed(cases)):
        elapsed, pixels = sample(binary, opcode, args.count)
        if reference is None: reference = pixels
        assert pixels == reference, "Rendered outputs differ"
        samples[label].append(elapsed)
for label, timings in samples.items():
    print(f"{label}: {args.count} requests, {statistics.median(timings):.6f}s (median of {args.runs}); identical pixels")
