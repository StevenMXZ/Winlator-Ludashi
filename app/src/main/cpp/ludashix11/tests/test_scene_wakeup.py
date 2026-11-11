#!/usr/bin/env python3
"""A coalesced scene must progress after both GPU slots are occupied."""
import os
import pathlib
import re
import socket
import struct
import subprocess
import sys
import tempfile
import time
from test_wire import Client


def run(binary):
    channel, child = socket.socketpair()
    with tempfile.TemporaryDirectory(prefix="ludashi-wakeup-") as directory:
        path = pathlib.Path(directory) / "server.log"
        with path.open("w+") as log:
            process = subprocess.Popen([binary, str(child.fileno())],
                pass_fds=[child.fileno()], stdout=subprocess.PIPE, stderr=log, text=True,
                env=dict(os.environ, LUDASHI_HOST_TRACE_SCENE="1",
                         LUDASHI_HOST_FENCE_DELAY_MS="500", WINLATOR_X11="0"))
            child.close()
            try:
                assert process.stdout.readline().strip() == "ready"
                c = Client(channel)
                win, pixmap = c.base | 1, c.base | 2
                c.send(1, payload=struct.pack("<IIhhHHHHII", win, c.root, 0, 0, 64, 64, 0, 1, 0, 0))
                c.send(8, payload=struct.pack("<I", win))
                c.send(53, 24, struct.pack("<IIHH", pixmap, win, 64, 64))
                present = c.extension("Present")
                c.send(present, 3, struct.pack("<III", c.base | 3, win, 6))
                c.barrier()
                time.sleep(.05)  # Let the initial software-only scene drain.

                def submit(serial):
                    c.send(present, 1, struct.pack("<IIIIIhhIIIIIQQQ", win, pixmap, serial,
                           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                    for kind in (2, 1):
                        event = c.packet()
                        assert event[0] == 35, event.hex()
                        assert struct.unpack_from("<H", event, 8)[0] == kind, event.hex()
                        assert struct.unpack_from("<I", event, 20)[0] == serial, event.hex()

                def sampled(serial):
                    deadline = time.monotonic() + 3
                    while f"HostScene serial={serial} " not in path.read_text():
                        assert time.monotonic() < deadline, path.read_text()
                        time.sleep(.002)

                # Fill both sampling slots before replacing the pending scene.
                submit(1); sampled(1)
                submit(2); sampled(2)
                for serial in range(3, 131):
                    submit(serial)
                # A cursor-only patch must survive in that same pending scene.
                c.send(41, payload=struct.pack("<IIhhHHhh", 0, c.root, 0, 0, 0, 0, 723, 411))
                c.barrier()
                # No more producer input: GPU retirement alone must wake Draw.
                sampled(130)
                assert re.search(r"HostScene serial=130 .*cursor_x=708 cursor_y=396", path.read_text()), path.read_text()
                c.close()
                print("PASS: latest Present and cursor progress after GPU-slot saturation without further input")
            finally:
                process.terminate(); process.wait(10); channel.close()
                assert process.returncode == 0, path.read_text()
                assert "ERROR: AddressSanitizer" not in path.read_text(), path.read_text()
                assert "runtime error:" not in path.read_text(), path.read_text()


if __name__ == "__main__":
    run(sys.argv[1])
