#!/usr/bin/env python3
"""Compare the cost of mapping and unmapping the same set of painted children."""
import argparse
from contextlib import closing
import socket
import statistics
import struct
import subprocess
import tempfile
import time

from test_wire import Client


def sample(binary, child_count, cycles):
    channel, peer = socket.socketpair()
    with channel, peer, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            [binary, str(peer.fileno())], pass_fds=(peer.fileno(),),
            stdout=subprocess.PIPE, stderr=log, text=True,
        )
        peer.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            with closing(Client(channel)) as c:
                parent, gc = c.base | 1, c.base | 2
                c.send(1, payload=struct.pack("<IIhhHHHHII", parent, c.root, 100, 100, 640, 480, 0, 1, 0, 0))
                c.send(8, payload=struct.pack("<I", parent))
                c.send(55, payload=struct.pack("<IIII", gc, parent, 4, 0x123456))
                for i in range(child_count):
                    wid = c.base | (i + 3)
                    c.send(1, payload=struct.pack("<IIhhHHHHII", wid, parent, (i % 20) * 16, (i // 20) * 16,
                                                   16, 16, 0, 1, 0, 0))
                    c.send(70, payload=struct.pack("<IIhhHH", wid, gc, 0, 0, 16, 16))
                c.barrier()
                both = struct.pack("<BBHIBBHI", 9, 0, 2, parent, 11, 0, 2, parent)
                start = time.perf_counter()
                c.sock.sendall(both * cycles)
                c.seq += cycles * 2
                c.barrier()
                elapsed = time.perf_counter() - start
                c.send(9, payload=struct.pack("<I", parent))
                for i in range(child_count):
                    reply = c.reply(c.send(3, payload=struct.pack("<I", c.base | (i + 3))))
                    assert reply[26] == 2, reply.hex()
                return elapsed
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            assert process.returncode == 0, log.read()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("current")
    parser.add_argument("--runs", type=int, default=9)
    parser.add_argument("--cycles", type=int, default=100)
    parser.add_argument("--children", type=int, default=96)
    args = parser.parse_args()
    if not 1 <= args.children <= 600 or not 1 <= args.cycles <= 25000 or args.runs < 1:
        parser.error("children must be 1..600, cycles 1..25000, and runs positive")
    builds = {args.baseline: [], args.current: []}
    for run in range(args.runs):
        for binary in (builds if run % 2 == 0 else reversed(builds)):
            builds[binary].append(sample(binary, args.children, args.cycles))
    for binary, timings in builds.items():
        print(f"{binary}: {statistics.median(timings) * 1000:.3f} ms "
              f"({args.cycles} map/unmap cycles, {args.children} children, median of {args.runs})")
