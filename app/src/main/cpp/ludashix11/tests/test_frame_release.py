#!/usr/bin/env python3
"""GPU resource destruction must not occupy the X11 request or scene lock."""
import array
import os
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import time
from test_wire import Client, exact


def run(binary):
    with tempfile.TemporaryDirectory(prefix="ludashi-frame-release-") as directory:
        channel, child = socket.socketpair()
        logpath = pathlib.Path(directory) / "server.log"
        with open(logpath, "w+") as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True,
                env=dict(os.environ, LUDASHI_HOST_FENCE_DELAY_MS="250",
                         LUDASHI_HOST_RELEASE_DELAY_MS="600", LUDASHI_HOST_TRACE_SCENE="1"))
            child.close()
            try:
                assert process.stdout.readline().strip() == "ready"
                c = Client(channel)
                win, gpu, cpu, eid = (c.base | i for i in (1, 2, 3, 4))
                c.send(1, payload=struct.pack("<IIhhHHHHII", win, c.root, 0, 0, 64, 64, 0, 1, 0, 0))
                c.send(8, payload=struct.pack("<I", win))
                c.send(53, 24, struct.pack("<IIHH", cpu, win, 64, 64))
                dri3, present = c.extension("DRI3"), c.extension("Present")
                c.send(present, 3, struct.pack("<III", eid, win, 6)); c.barrier()
                transfer, producer = socket.socketpair()
                producer.settimeout(3)
                payload = struct.pack("<IIIHHHBB", gpu, win, 64 * 64 * 4, 64, 64, 256, 24, 32)
                c.seq += 1
                c.sock.sendmsg([struct.pack("<BBH", dri3, 2, 6) + payload],
                    [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [transfer.fileno()]))])
                transfer.close()
                assert exact(producer, 1) == b"\x01"
                producer.sendall(struct.pack("<IIIIQIIQ", 64, 64, 1, 1, 0, 64, 0, 0))
                producer.close(); c.barrier()

                def submit(pixmap, serial):
                    c.send(present, 1, struct.pack("<IIIIIhhIIIIIQQQ", win, pixmap, serial,
                           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                    for kind in (2, 1):
                        packet = c.packet()
                        assert packet[0] == 35 and struct.unpack_from("<H", packet, 8)[0] == kind, packet.hex()
                        assert struct.unpack_from("<I", packet, 20)[0] == serial, packet.hex()

                def wait_for(marker):
                    deadline = time.monotonic() + 3
                    while marker not in logpath.read_text() and time.monotonic() < deadline:
                        time.sleep(.002)
                    assert marker in logpath.read_text(), logpath.read_text()

                submit(gpu, 100)
                wait_for("HostScene serial=100 ")
                selected = time.monotonic()
                submit(cpu, 101)
                c.send(54, payload=struct.pack("<I", gpu)); c.barrier()
                # Replacing/freeing a selected pixmap cannot destroy its AHB
                # before the modeled GPU sampling fence completes.
                assert "HostBufferRelease begin" not in logpath.read_text(), logpath.read_text()
                wait_for("HostBufferRelease begin")
                assert time.monotonic() - selected >= .15
                assert "HostBufferRelease end" not in logpath.read_text(), logpath.read_text()
                start = time.monotonic()
                c.barrier()
                submit(cpu, 102)
                c.barrier()
                elapsed = time.monotonic() - start
                assert elapsed < .20, f"X11 blocked on resource destruction: {elapsed:.3f}s"
                wait_for("HostScene serial=102 ")
                assert "HostBufferRelease end" not in logpath.read_text(), logpath.read_text()
                wait_for("HostBufferRelease end")
                c.close()
                print(f"PASS: GPU lifetime retained; replies, Presents and scene updates continue during 600 ms resource destruction ({elapsed:.4f}s)")
            finally:
                process.terminate()
                try: process.wait(10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(); raise
                channel.close(); log.seek(0); output = log.read()
                assert process.returncode == 0, output
                assert "ERROR: AddressSanitizer" not in output and "runtime error:" not in output, output


if __name__ == "__main__":
    run(sys.argv[1])
