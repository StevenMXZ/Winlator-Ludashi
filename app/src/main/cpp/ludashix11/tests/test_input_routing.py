#!/usr/bin/env python3
"""Focus boundaries and consistent confined pointer coordinates across the wire APIs."""
import os
import socket
import struct
import subprocess
import sys
import tempfile

from test_wire import Client


def run(binary):
    channel, child = socket.socketpair()
    with channel, child, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen([binary, str(child.fileno())], pass_fds=(child.fileno(),),
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
                                   env=dict(os.environ, LUDASHI_HOST_INPUT="1"))
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            c = Client(channel)
            with c.sock:
                parent, focused, confined = [c.base | i for i in range(1, 4)]
                def create(window, above, x, y, width, height, kind=1):
                    c.send(1, payload=struct.pack("<IIhhHHHHII", window, above, x, y, width, height, 0, kind, 0, 0))
                    c.send(8, payload=struct.pack("<I", window))
                def select(window, mask):
                    c.send(2, payload=struct.pack("<III", window, 1 << 11, mask))
                def focus(window):
                    c.send(42, payload=struct.pack("<II", window, 0))
                def warp(x, y):
                    c.send(41, payload=struct.pack("<IIhhHHhh", 0, c.root, 0, 0, 0, 0, x, y))
                def inject(command):
                    process.stdin.write(command + "\n")
                    process.stdin.flush()
                    assert process.stdout.readline().strip() == "input"
                def quiet():
                    sequence = c.send(43)
                    packet = c.packet()
                    assert packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == sequence, packet.hex()
                def event(kind, target, x, y, child_id=0):
                    packet = c.packet()
                    assert packet[0] == kind, packet.hex()
                    assert struct.unpack_from("<IIIhh", packet, 8) == (c.root, target, child_id, x, y), packet.hex()
                def position(x, y):
                    reply = c.reply(c.send(38, payload=struct.pack("<I", c.root)))
                    assert struct.unpack_from("<hh", reply, 16) == (x, y), reply.hex()
                create(parent, c.root, 100, 100, 100, 100)
                create(focused, parent, 10, 10, 32, 32)
                create(confined, c.root, 300, 200, 40, 30, 2)
                select(c.root, 3)
                select(parent, 3)
                focus(focused)
                warp(115, 115)
                quiet()
                for pressed in (1, 0):
                    inject(f"key 38 {pressed}")
                    quiet()  # An unselected focused window must not leak keys to its parent.
                select(focused, 3)
                quiet()
                for pressed, kind in ((1, 2), (0, 3)):
                    inject(f"key 38 {pressed}")
                    event(kind, focused, 115, 115)
                select(focused, 0)
                focus(parent)
                quiet()
                for pressed, kind in ((1, 2), (0, 3)):
                    inject(f"key 38 {pressed}")
                    event(kind, parent, 115, 115, focused)
                focus(0)
                quiet()
                for pressed in (1, 0):
                    inject(f"key 38 {pressed}")
                    quiet()

                focus(focused)
                select(focused, 3)
                mask = (1 << 2) | (1 << 3) | (1 << 6)
                reply = c.reply(c.send(26, 0, struct.pack("<IHBBIII", c.root, mask, 1, 1, confined, 0, 0)))
                assert reply[1] == 0, reply.hex()
                warp(900, 900)
                event(6, c.root, 339, 229)
                position(339, 229)
                c.send(41, payload=struct.pack("<IIhhHHhh", 0, 0, 0, 0, 0, 0, -10, -10))
                event(6, c.root, 329, 219)
                position(329, 219)
                warp(900, 900)
                event(6, c.root, 339, 229)
                # The WarpPointer source region also uses the confined position.
                c.send(41, payload=struct.pack("<IIhhHHhh", confined, c.root, 0, 0, 0, 0, 100, 100))
                event(6, c.root, 300, 200)
                position(300, 200)
                warp(900, 900)
                event(6, c.root, 339, 229)
                for pressed, kind in ((1, 4), (0, 5)):
                    inject(f"button 1 {pressed}")
                    event(kind, c.root, 339, 229)
                for pressed, kind in ((1, 2), (0, 3)):
                    inject(f"key 38 {pressed}")
                    event(kind, focused, 339, 229)
                # Confinement follows the current geometry, including moves without new mouse input.
                c.send(12, payload=struct.pack("<IHHII", confined, 3, 0, 400, 300))
                quiet()
                position(439, 329)
                for pressed, kind in ((1, 4), (0, 5)):
                    inject(f"button 1 {pressed}")
                    event(kind, c.root, 439, 329)
                c.send(10, payload=struct.pack("<I", confined))
                quiet()
                position(900, 719)  # QueryPointer clips ordinary overscroll to the root.
                # Pointer-root focus still targets the deepest selected window under the pointer.
                focus(1)
                warp(115, 115)
                quiet()
                for pressed, kind in ((1, 2), (0, 3)):
                    inject(f"key 38 {pressed}")
                    event(kind, focused, 115, 115)
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: keyboard focus boundary/None/PointerRoot and matching confined motion/query/button/key coordinates across moves and unmap")


if __name__ == "__main__":
    run(sys.argv[1])
