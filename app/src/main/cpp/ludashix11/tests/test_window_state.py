#!/usr/bin/env python3
"""Window-tree invariants, rejected reparents, pointer stacking and property clock consistency."""
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
                                   stdout=subprocess.PIPE, stderr=log, text=True)
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            c = Client(channel)
            with c.sock:
                parent, nested, sibling = [c.base | i for i in (1, 2, 3)]
                def create(window, above, x, y, kind=1):
                    c.send(1, payload=struct.pack("<IIhhHHHHII", window, above, x, y, 32, 32, 0, kind, 0, 0))
                    c.send(8, payload=struct.pack("<I", window))
                create(parent, c.root, 10, 20)
                create(nested, parent, 2, 3)
                create(sibling, c.root, 10, 20)
                c.barrier()

                def tree(window):
                    reply = c.reply(c.send(15, payload=struct.pack("<I", window)))
                    count = struct.unpack_from("<H", reply, 16)[0]
                    return struct.unpack_from("<" + "I" * count, reply, 32)
                def error(code, opcode, payload):
                    c.send(opcode, payload=payload)
                    packet = c.packet()
                    assert packet[0] == 0 and packet[1] == code, packet.hex()
                    c.barrier()
                for window, destination in [(parent, parent), (parent, nested), (c.root, parent)]:
                    error(8, 7, struct.pack("<IIhh", window, destination, 0, 0))
                assert tree(c.root) == (parent, sibling)
                assert tree(parent) == (nested,)
                def stack(window, mode, above=None):
                    values = (mode,) if above is None else (above, mode)
                    c.send(12, payload=struct.pack("<IHH", window, 64 if above is None else 96, 0) +
                           struct.pack("<" + "I" * len(values), *values))
                for above in (None, sibling):
                    stack(parent, 2, above)  # TopIf: overlapping window above it.
                    assert tree(c.root) == (sibling, parent)
                    stack(parent, 2, above)  # Already above: stable order.
                    assert tree(c.root) == (sibling, parent)
                    stack(parent, 3, above)  # BottomIf: overlapping window below it.
                    assert tree(c.root) == (parent, sibling)
                    stack(parent, 3, above)
                    assert tree(c.root) == (parent, sibling)
                    stack(parent, 4, above)
                    assert tree(c.root) == (sibling, parent)
                    stack(parent, 4, above)
                    assert tree(c.root) == (parent, sibling)
                c.send(12, payload=struct.pack("<IHHI", sibling, 1, 0, 100))
                stack(parent, 2)
                assert tree(c.root) == (parent, sibling)  # No overlap: do not raise.
                c.send(12, payload=struct.pack("<IHHI", sibling, 1, 0, 10))
                c.send(41, payload=struct.pack("<IIhhHHhh", 0, c.root, 0, 0, 0, 0, 15, 25))
                pointer = c.reply(c.send(38, payload=struct.pack("<I", c.root)))
                assert struct.unpack_from("<I", pointer, 12)[0] == sibling
                c.send(10, payload=struct.pack("<I", sibling))
                pointer = c.reply(c.send(38, payload=struct.pack("<I", c.root)))
                assert struct.unpack_from("<I", pointer, 12)[0] == parent

                c.send(2, payload=struct.pack("<III", nested, 1 << 11, 1 << 22))
                name = b"WINDOW_CLOCK"
                reply = c.reply(c.send(16, payload=struct.pack("<HH", len(name), 0) + name))
                atom = struct.unpack_from("<I", reply, 8)[0]
                c.send(18, payload=struct.pack("<IIIB3xI", nested, atom, 31, 8, 1) + b"a\0\0\0")
                event = c.packet()
                assert event[0] == 28 and event[16] == 0, event.hex()
                created = struct.unpack_from("<I", event, 12)[0]
                c.send(19, payload=struct.pack("<II", nested, atom))
                event = c.packet()
                assert event[0] == 28 and event[16] == 1, event.hex()
                deleted = struct.unpack_from("<I", event, 12)[0]
                assert (deleted - created) & 0xffffffff < 5000, (created, deleted)

                input_only = c.base | 4
                create(input_only, c.root, 0, 0, 2)
                c.barrier()
                error(8, 7, struct.pack("<IIhh", parent, input_only, 0, 0))
                error(8, 2, struct.pack("<III", input_only, 2, 0))
                error(8, 12, struct.pack("<IHHII", input_only, 17, 0, 99, 0))
                error(9, 73, struct.pack("<IhhHHI", input_only, 0, 0, 1, 1, 0xffffffff))
                c.send(12, payload=struct.pack("<IHHII", input_only, 12, 0, 64, 48))
                error(9, 73, struct.pack("<IhhHHI", input_only, 0, 0, 1, 1, 0xffffffff))
                geometry = c.reply(c.send(14, payload=struct.pack("<I", input_only)))
                assert struct.unpack_from("<hhHHH", geometry, 12) == (0, 0, 64, 48, 0)
                error(8, 12, struct.pack("<IHHI", parent, 32, 0, sibling))
                error(2, 12, struct.pack("<IHHI", parent, 64, 0, 5))
                error(2, 12, struct.pack("<IHHI", parent, 16, 0, 65536))
                geometry = c.reply(c.send(14, payload=struct.pack("<I", parent)))
                assert struct.unpack_from("<hhHHH", geometry, 12) == (10, 20, 32, 32, 0)
                assert tree(parent) == (nested,)
                c.send(7, payload=struct.pack("<IIhh", nested, c.root, 50, 60))
                c.barrier()
                assert tree(parent) == ()
                geometry = c.reply(c.send(14, payload=struct.pack("<I", nested)))
                assert struct.unpack_from("<hh", geometry, 12) == (50, 60)

                # InputOnly helpers receive input but must not obscure drawable windows.
                visible, helper, cover = [c.base | i for i in (5, 6, 7)]
                create(visible, c.root, 300, 300)
                c.send(2, payload=struct.pack("<III", visible, 1 << 11, 1 << 16))
                c.send(10, payload=struct.pack("<I", visible))
                c.send(8, payload=struct.pack("<I", visible))
                def visibility(state):
                    packet = c.packet()
                    assert packet[0] == 15 and struct.unpack_from("<I", packet, 4)[0] == visible, packet.hex()
                    assert packet[8] == state, packet.hex()
                def quiet():
                    sequence = c.send(43)
                    packet = c.packet()
                    assert packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == sequence, packet.hex()
                visibility(0)
                create(helper, c.root, 300, 300, 2)
                quiet()
                create(cover, c.root, 300, 300)
                visibility(2)
                stack(cover, 1, visible)
                visibility(0)
                stack(cover, 0, visible)
                visibility(2)
                c.send(10, payload=struct.pack("<I", cover))
                visibility(0)
                quiet()
                c.send(2, payload=struct.pack("<III", visible, 1 << 11, 1 << 17))
                stack(visible, 0)
                packet = c.packet()
                assert packet[0] == 22, packet.hex()
                assert struct.unpack_from("<III", packet, 4) == (visible, visible, helper), packet.hex()
                quiet()
                stack(visible, 0)  # Already top: no state change or ConfigureNotify.
                quiet()


                moving = c.base | 8
                c.send(1, payload=struct.pack("<IIhhHHHHII", moving, parent, 0, 0, 8, 8, 0, 1, 0, 0))
                c.send(2, payload=struct.pack("<IIII", moving, (1 << 9) | (1 << 11), 1, 1 << 17))
                c.send(2, payload=struct.pack("<III", parent, 1 << 11, 1 << 19))
                c.barrier()
                observer = Client(channel)
                with observer.sock:
                    observer.send(2, payload=struct.pack("<III", moving, 1 << 11, 1 << 17))
                    observer.send(2, payload=struct.pack("<III", sibling, 1 << 11, 1 << 19))
                    observer.barrier()
                    sent = c.send(7, payload=struct.pack("<IIhh", moving, sibling, -3, 7))
                    for connection, serial, scopes in ((c, sent, (moving, parent)),
                                                        (observer, observer.seq, (moving, sibling))):
                        for scope in scopes:
                            expected = struct.pack("<BBHIIIhhB11x", 21, 0, serial, scope, moving, sibling, -3, 7, 1)
                            packet = connection.packet()
                            assert packet == expected, packet.hex()
                        connection.barrier()
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: reparent cycles/root/class rejection, conditional stacking, pointer hit-test, property clocks, geometry and InputOnly visibility")


if __name__ == "__main__":
    run(sys.argv[1])
