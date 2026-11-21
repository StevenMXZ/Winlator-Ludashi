#!/usr/bin/env python3
"""Measure pipelined core X11 replies over a local socket using the host backend."""

import argparse
import os
import resource
import socket
import statistics
import struct
import subprocess
import tempfile
import time

from test_wire import Client, exact


BATCH_SIZE = 4096
BATCH_COUNT = 64
GET_INPUT_FOCUS = struct.pack("<BBH", 43, 0, 1)


def child_cpu():
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    return usage.ru_utime + usage.ru_stime


def measure(binary):
    channel, child = socket.socketpair()
    with channel, child, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            [binary, str(child.fileno())], pass_fds=(child.fileno(),),
            stdout=subprocess.PIPE, stderr=log, text=True, env=dict(os.environ, WINLATOR_X11="0"),
        )
        child.close()
        cpu_before = child_cpu()
        try:
            assert process.stdout.readline().strip() == "ready"
            client = Client(channel)
            with client.sock:
                requests = GET_INPUT_FOCUS * BATCH_SIZE
                start = time.monotonic()
                for batch in range(BATCH_COUNT):
                    client.sock.sendall(requests)
                    replies = exact(client.sock, BATCH_SIZE * 32)
                    sequence = (batch + 1) * BATCH_SIZE & 0xffff
                    assert replies[-32] == 1
                    assert struct.unpack_from("<H", replies, len(replies) - 30)[0] == sequence
                elapsed = time.monotonic() - start
        finally:
            process.terminate()
            process.wait(10)
            process.stdout.close()
            log.seek(0)
            assert process.returncode == 0, log.read()
        return elapsed, child_cpu() - cpu_before


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", nargs="+")
    parser.add_argument("--runs", type=int, default=7)
    args = parser.parse_args()
    samples = {binary: [] for binary in args.binary}
    for _ in range(args.runs):
        for binary in args.binary:
            samples[binary].append(measure(binary))
    for binary, measurements in samples.items():
        elapsed, cpu = map(statistics.median, zip(*measurements))
        print(f"{binary}: {BATCH_SIZE * BATCH_COUNT} replies, elapsed={elapsed:.3f}s "
              f"server_cpu={cpu:.3f}s (median of {args.runs})")


if __name__ == "__main__":
    main()
