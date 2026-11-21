#!/usr/bin/env python3
"""Native X11 Present idle fences must pace the producer, not the compositor."""
import os
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import time
from test_wire import Client

def run(binary):
    with tempfile.TemporaryDirectory(prefix="ludashix11-limit-") as directory:
        channel, child = socket.socketpair()
        log = open(pathlib.Path(directory) / "server.log", "w+")
        env = dict(os.environ, LUDASHI_HOST_FPS_LIMIT="30", LUDASHI_HOST_INPUT="1")
        process = subprocess.Popen([binary, str(child.fileno())],
                                   pass_fds=[child.fileno()],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True, env=env)
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            c = Client(channel)
            win, p0, p1 = c.base | 1, c.base | 2, c.base | 3
            c.send(1, payload=struct.pack("<IIhhHHHHII", win, c.root, 0, 0, 64, 64, 0, 1, 0, 0))
            c.send(8, payload=struct.pack("<I", win))
            c.send(53, 24, struct.pack("<IIHH", p0, win, 64, 64))
            c.send(53, 24, struct.pack("<IIHH", p1, win, 64, 64))
            present = c.extension("Present")
            c.send(present, 3, struct.pack("<III", c.base | 4, win, 6))
            c.barrier()
            def submit(pixmap, serial):
                return c.send(present, 1,
                    struct.pack("<IIIIIhhIIIIIQQQ", win, pixmap, serial, 0, 0,
                                0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            start = time.monotonic()
            submit(p0, 10)
            submit(p1, 11)
            # Both submissions can complete promptly, but the first buffer's
            # IDLE must not arrive before its 30 FPS budget (~33 ms).
            idle_at = None
            for _ in range(8):
                packet = c.packet()
                assert packet[0] != 0, packet.hex()
                if packet[0] == 35 and struct.unpack_from("<H", packet, 8)[0] == 2:
                    idle_at = time.monotonic()
                    break
            assert idle_at is not None, "No PresentIdleNotify after replacing the pixmap"
            elapsed = idle_at - start
            assert elapsed >= 0.020, f"Producer IDLE was not paced: {elapsed:.4f}s"
            assert elapsed < 1.5, f"Producer IDLE stalled: {elapsed:.4f}s"
            # Queue more than a second of producer delay. Draining on a live
            # limiter change must wake the server and release every buffer.
            serials = list(range(100, 140))
            for serial in serials:
                submit(p0 if serial % 2 == 0 else p1, serial)
            marker = c.send(43)
            released = []
            def record_idle(packet):
                if packet[0] == 35 and struct.unpack_from("<H", packet, 8)[0] == 2:
                    serial = struct.unpack_from("<I", packet, 20)[0]
                    if serial in serials:
                        released.append(serial)
            while True:
                packet = c.packet()
                if packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == marker:
                    break
                record_idle(packet)
            assert len(released) < len(serials)
            stop_limit = time.monotonic()
            process.stdin.write("cursor 1\n")
            process.stdin.flush()
            assert process.stdout.readline().strip() == "input"
            while len(released) < len(serials):
                record_idle(c.packet())
            assert released == serials, released
            assert time.monotonic() - stop_limit < 0.9
            c.close()
            print(f"PASS: 30 FPS paces IDLE ({elapsed:.3f}s) and disabling drains pending releases in order")
        finally:
            process.terminate()
            try:
                process.wait(10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise
            log.seek(0)
            output = log.read()
            log.close()
            assert process.returncode == 0, output
            assert "ERROR: AddressSanitizer" not in output and "runtime error:" not in output, output

if __name__ == "__main__":
    run(sys.argv[1])
