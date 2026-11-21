#!/usr/bin/env python3
"""Exercise crossing masks, border-relative coordinates and grab transition modes on the wire."""
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
                a, nested_a, b, nested_b = [c.base | i for i in range(1, 5)]
                def create(window, parent, x, y, width, border):
                    c.send(1, payload=struct.pack("<IIhhHHHHII", window, parent, x, y,
                                                 width, width, border, 1, 0, 0))
                    c.send(8, payload=struct.pack("<I", window))
                def select(window, mask):
                    c.send(2, payload=struct.pack("<III", window, 1 << 11, mask))
                def warp(x, y):
                    c.send(41, payload=struct.pack("<IIhhHHhh", 0, c.root, 0, 0, 0, 0, x, y))
                def quiet():
                    sequence = c.send(43)
                    packet = c.packet()
                    assert packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == sequence, packet.hex()
                def crossing(kind, detail, window, child_id, root_pos, local_pos, mode=0):
                    packet = c.packet()
                    assert packet[0:2] == bytes((kind, detail)), packet.hex()
                    assert struct.unpack_from("<IIIhhhh", packet, 8) == (
                        c.root, window, child_id, *root_pos, *local_pos), packet.hex()
                    assert packet[30] == mode and packet[31] == 3, packet.hex()
                create(a, c.root, 100, 100, 100, 2)
                create(nested_a, a, 5, 7, 20, 3)
                create(b, c.root, 300, 100, 100, 1)
                create(nested_b, b, 20, 20, 20, 0)
                select(a, 1 << 5)  # LeaveWindow only.
                select(nested_a, 1 << 4)  # EnterWindow only.
                quiet()
                warp(110, 112)
                crossing(7, 0, nested_a, 0, (110, 112), (0, 0))
                quiet()
                warp(190, 190)
                quiet()  # Neither window selected the crossing event it would receive.
                warp(500, 500)
                crossing(8, 0, a, 0, (500, 500), (398, 398))
                quiet()

                for window in (a, nested_a, b, nested_b):
                    select(window, (1 << 4) | (1 << 5))
                warp(110, 112)
                crossing(7, 1, a, nested_a, (110, 112), (8, 10))
                crossing(7, 0, nested_a, 0, (110, 112), (0, 0))
                quiet()
                warp(325, 125)
                crossing(8, 3, nested_a, 0, (325, 125), (215, 13))
                crossing(8, 4, a, nested_a, (325, 125), (223, 23))
                crossing(7, 4, b, nested_b, (325, 125), (24, 24))
                crossing(7, 3, nested_b, 0, (325, 125), (4, 4))
                quiet()

                sequence = c.send(26, 0, struct.pack("<IHBBIII", a, 0, 1, 1, 0, 0, 0))
                crossing(8, 3, nested_b, 0, (325, 125), (4, 4), 1)
                crossing(8, 4, b, nested_b, (325, 125), (24, 24), 1)
                crossing(7, 3, a, 0, (325, 125), (223, 23), 1)
                reply = c.packet()
                assert reply[0:2] == b"\x01\x00" and struct.unpack_from("<H", reply, 2)[0] == sequence
                c.send(27, payload=struct.pack("<I", 0))
                crossing(8, 3, a, 0, (325, 125), (223, 23), 2)
                crossing(7, 4, b, nested_b, (325, 125), (24, 24), 2)
                crossing(7, 3, nested_b, 0, (325, 125), (4, 4), 2)
                quiet()

                # Key/motion propagation identifies the immediate child and honors do-not-propagate.
                for window in (a, nested_a, b, nested_b):
                    select(window, 0)
                select(a, 3 | (1 << 6))
                c.send(42, payload=struct.pack("<II", a, 0))
                warp(110, 112)
                quiet()
                def inject(command):
                    process.stdin.write(command + "\n")
                    process.stdin.flush()
                    assert process.stdout.readline().strip() == "input"
                for pressed, kind in ((1, 2), (0, 3)):
                    inject(f"key 38 {pressed}")
                    packet = c.packet()
                    assert packet[:2] == bytes((kind, 38)), packet.hex()
                    assert struct.unpack_from("<II", packet, 12) == (a, nested_a), packet.hex()
                    assert struct.unpack_from("<hh", packet, 24) == (8, 10), packet.hex()
                c.send(2, payload=struct.pack("<III", nested_a, 1 << 12, 3))
                quiet()
                for pressed in (1, 0):
                    inject(f"key 38 {pressed}")
                    quiet()
                c.send(2, payload=struct.pack("<III", nested_a, 1 << 12, 0))
                warp(111, 113)
                packet = c.packet()
                assert packet[0] == 6 and struct.unpack_from("<II", packet, 12) == (a, nested_a), packet.hex()
                assert struct.unpack_from("<hh", packet, 24) == (9, 11), packet.hex()
                quiet()
                for button in (4, 5):
                    select(a, 1 << (button + 7))
                    inject(f"button {button} 1")
                    quiet()
                    warp(110 + button, 112 + button)
                    packet = c.packet()
                    assert packet[0] == 6 and struct.unpack_from("<I", packet, 12)[0] == a, packet.hex()
                    assert struct.unpack_from("<H", packet, 28)[0] == 1 << (button + 7), packet.hex()
                    inject(f"button {button} 0")
                    warp(120 + button, 122 + button)
                    quiet()  # Button-specific motion selection stops after release.
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: independent Enter/Leave masks, crossing details/order/child, borders, grab modes, key/motion propagation and suppression")


if __name__ == "__main__":
    run(sys.argv[1])
