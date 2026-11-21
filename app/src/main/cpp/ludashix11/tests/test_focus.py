#!/usr/bin/env python3
"""Check X11 focus paths, pointer details, timestamps, viewability and deep trees."""
import socket
import struct
import subprocess
import sys
import tempfile
import time

from test_wire import Client


def run(binary):
    channel, child = socket.socketpair()
    with channel, child, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen([binary, str(child.fileno())], pass_fds=(child.fileno(),),
                                   stdout=subprocess.PIPE, stderr=log, text=True)
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            c = Client(channel)
            with c.sock:
                root = c.root
                a, a1, a2, aside, b, b1, hidden = [c.base | i for i in range(1, 8)]
                def create(window, parent, x=0, y=0, width=32, mapped=True):
                    c.send(1, payload=struct.pack("<IIhhHHHHII", window, parent, x, y,
                                                 width, width, 0, 1, 0, 0))
                    if mapped: c.send(8, payload=struct.pack("<I", window))
                def select(window, mask=1 << 21):
                    c.send(2, payload=struct.pack("<III", window, 1 << 11, mask))
                def quiet():
                    sequence = c.send(43)
                    packet = c.packet()
                    assert packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == sequence, packet.hex()
                def focus(window, expected, revert=0, timestamp=0):
                    c.send(42, revert, struct.pack("<II", window, timestamp))
                    for kind, detail, target in expected:
                        packet = c.packet()
                        assert (packet[0], packet[1], struct.unpack_from("<I", packet, 4)[0]) == (
                            kind, detail, target), packet.hex()
                        assert packet[8] == 0, packet.hex()
                    quiet()
                def current(window, revert=0):
                    reply = c.reply(c.send(43))
                    assert reply[1] == revert and struct.unpack_from("<I", reply, 8)[0] == window, reply.hex()
                def error(window, revert, code):
                    c.send(42, revert, struct.pack("<II", window, 0))
                    packet = c.packet()
                    assert packet[:2] == bytes((0, code)), packet.hex()
                    quiet()
                create(a, root, 100, 100, 120)
                create(a1, a, 10, 10, 70)
                create(a2, a1, 10, 10, 20)
                create(aside, a, 80, 80, 20)
                create(b, root, 300, 100, 100)
                create(b1, b, 10, 10, 30)
                create(hidden, root, mapped=False)
                c.send(41, payload=struct.pack("<IIhhHHhh", 0, root, 0, 0, 0, 0, 0, 0))
                c.send(42, payload=struct.pack("<II", 0, 0))
                c.barrier()
                for window in (root, a, a1, a2, aside, b, b1): select(window)
                focus(a2, [(10, 7, root), (9, 4, root), (9, 4, a), (9, 4, a1), (9, 3, a2)])
                focus(a, [(10, 0, a2), (10, 1, a1), (9, 2, a)])
                focus(a2, [(10, 2, a), (9, 1, a1), (9, 0, a2)])
                focus(b1, [(10, 3, a2), (10, 4, a1), (10, 4, a), (9, 4, b), (9, 3, b1)])
                focus(0, [(10, 3, b1), (10, 4, b), (10, 4, root), (9, 7, root)])
                focus(1, [(10, 7, root), (9, 6, root), (9, 5, root)])
                focus(0, [(10, 5, root), (10, 6, root), (9, 7, root)])
                focus(1, [(10, 7, root), (9, 6, root), (9, 5, root)])
                focus(a2, [(10, 5, root), (10, 6, root), (9, 4, root), (9, 4, a), (9, 4, a1), (9, 3, a2)])
                focus(1, [(10, 3, a2), (10, 4, a1), (10, 4, a), (10, 4, root), (9, 6, root), (9, 5, root)])

                # Pointer focus notifications occur only on the separate pointer branch.
                c.send(41, payload=struct.pack("<IIhhHHhh", 0, root, 0, 0, 0, 0, 185, 185))
                c.send(42, payload=struct.pack("<II", 0, 0))
                c.barrier()
                focus(a, [(10, 7, root), (9, 4, root), (9, 3, a), (9, 5, aside)])
                focus(a2, [(10, 5, aside), (10, 2, a), (9, 1, a1), (9, 0, a2)])
                focus(a, [(10, 0, a2), (10, 1, a1), (9, 2, a), (9, 5, aside)])
                focus(b1, [(10, 5, aside), (10, 3, a), (9, 4, b), (9, 3, b1)])
                focus(a, [(10, 3, b1), (10, 4, b), (9, 3, a), (9, 5, aside)])
                error(hidden, 0, 8)
                error(a2, 3, 2)
                current(a)

                # Obtain server-relative time through a real PropertyNotify event.
                select(hidden, 1 << 22)
                name = b"_LUDASHI_FOCUS_CLOCK"
                reply = c.reply(c.send(16, payload=struct.pack("<HH", len(name), 0) + name + b"\0" * (-len(name) % 4)))
                atom = struct.unpack_from("<I", reply, 8)[0]
                def clock():
                    c.send(18, payload=struct.pack("<IIIB3xI", hidden, atom, 31, 8, 1) + b"a\0\0\0")
                    packet = c.packet()
                    assert packet[0] == 28, packet.hex()
                    return struct.unpack_from("<I", packet, 12)[0]
                old_time = clock()
                time.sleep(.01)
                focus(b1, [(10, 5, aside), (10, 3, a), (9, 4, b), (9, 3, b1)])
                focus(a2, [], timestamp=old_time)
                focus(a2, [], timestamp=(clock() + 1000000) & 0xffffffff)
                current(b1)
                focus(a2, [(10, 3, b1), (10, 4, b), (9, 4, a), (9, 4, a1), (9, 3, a2)],
                      revert=2, timestamp=clock())
                c.send(10, payload=struct.pack("<I", a1))
                c.barrier()
                current(a)  # Skip the unviewable parent when reverting to an ancestor.
                c.send(8, payload=struct.pack("<I", a1))
                c.send(42, payload=struct.pack("<II", a2, 0))
                c.barrier()

                chain = [c.base | i for i in range(20, 60)]
                for index, window in enumerate(chain):
                    create(window, b1 if index == 0 else chain[index - 1])
                    select(window)
                quiet()
                focus(chain[-1], [(10, 3, a2), (10, 4, a1), (10, 4, a), (9, 4, b), (9, 4, b1)] +
                      [(9, 4, window) for window in chain[:-1]] + [(9, 3, chain[-1])])
                focus(a2, [(10, 3, chain[-1])] + [(10, 4, window) for window in reversed(chain[:-1])] +
                      [(10, 4, b1), (10, 4, b), (9, 4, a), (9, 4, a1), (9, 3, a2)])
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: focus ancestry/nonlinear/None/PointerRoot paths, pointer details, timestamps, validation, reversion and 40-level ordering")


if __name__ == "__main__":
    run(sys.argv[1])
