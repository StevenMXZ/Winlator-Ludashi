#!/usr/bin/env python3
"""Optional host Present throughput comparison; does not measure Android FPS."""
import os
import resource
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
from test_wire import Client


def measure(binary, windows=1, count=30000, roundtrip=False, cursor_motion=False):
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    cpu_start = usage.ru_utime + usage.ru_stime
    channel, child = socket.socketpair()
    with tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
            stdout=subprocess.PIPE, stderr=log, text=True, env=dict(os.environ, WINLATOR_X11="0"))
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            c = Client(channel)
            for index in range(windows):
                win = c.base | (index + 1)
                c.send(1, payload=struct.pack("<IIhhHHHHII", win, c.root, index * 8, 0, 64, 64, 0, 1, 0, 0))
                c.send(8, payload=struct.pack("<I", win))
            pixmaps = [c.base | (windows + i + 1) for i in range(3)]
            for pixmap in pixmaps: c.send(53, 24, struct.pack("<IIHH", pixmap, win, 64, 64))
            present = c.extension("Present")
            c.send(present, 3, struct.pack("<III", c.base | (windows + 4), win, 6))
            c.barrier()
            packets = []
            for serial in range(count):
                payload = struct.pack("<IIIIIhhIIIIIQQQ", win, pixmaps[serial % 3], serial,
                                      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                motion = struct.pack("<BBHIIhhHHhh", 41, 0, 6, 0, c.root,
                                     0, 0, 0, 0, 600 + serial % 200, 400) if cursor_motion else b""
                packets.append(motion + struct.pack("<BBH", present, 1, (len(payload) + 4) // 4) + payload)
            sequence = (c.seq + count * (2 if cursor_motion else 1) + 1) & 0xffff
            packets.append(struct.pack("<BBH", 43, 0, 1))
            batch = b"".join(packets)
            failure = []
            def read():
                try:
                    while True:
                        packet = c.packet()
                        assert packet[0] != 0, packet.hex()
                        if packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == sequence: break
                except Exception as error: failure.append(error)
            start = time.monotonic()
            if roundtrip:
                for packet in packets[:-1]:
                    c.sock.sendall(packet)
                    for kind in (2, 1):
                        event = c.packet()
                        assert event[0] == 35 and struct.unpack_from("<H", event, 8)[0] == kind, event.hex()
                c.sock.sendall(packets[-1])
                reply = c.packet()
                assert reply[0] == 1 and struct.unpack_from("<H", reply, 2)[0] == sequence, reply.hex()
            else:
                reader = threading.Thread(target=read)
                reader.start(); c.sock.sendall(batch); reader.join(30)
                assert not reader.is_alive() and not failure, failure
            elapsed = time.monotonic() - start
            c.close()
            process.terminate(); process.wait(10)
            usage = resource.getrusage(resource.RUSAGE_CHILDREN)
            cpu_used = usage.ru_utime + usage.ru_stime - cpu_start
            return elapsed, cpu_used
        finally:
            process.terminate(); process.wait(10); channel.close()
            log.seek(0); output = log.read()
            assert process.returncode == 0, output
            assert "ERROR: AddressSanitizer" not in output and "runtime error:" not in output, output


if __name__ == "__main__":
    roundtrip = "--roundtrip" in sys.argv[1:]
    cursor_motion = "--cursor-motion" in sys.argv[1:]
    for binary in (arg for arg in sys.argv[1:] if arg not in ("--roundtrip", "--cursor-motion")):
        for windows in (1, 24):
            elapsed, cpu = measure(binary, windows, roundtrip=roundtrip, cursor_motion=cursor_motion)
            print(f"{binary}: mode={'roundtrip' if roundtrip else 'burst'} cursor_motion={cursor_motion} windows={windows} presents=30000 elapsed={elapsed:.3f}s server_cpu={cpu:.3f}s")
