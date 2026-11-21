#!/usr/bin/env python3
"""Verify failed window creation rolls back IDs/listeners and InputOnly geometry works."""
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
                def create(window, parent=None, kind=1, depth=0, border=0, mask=0, values=()):
                    payload = struct.pack("<IIhhHHHHII", window, parent or c.root,
                                          -5, 3, 31, 17, border, kind, 0, mask)
                    payload += struct.pack("<" + "I" * len(values), *values)
                    return c.send(1, depth, payload)

                def children(window):
                    reply = c.reply(c.send(15, payload=struct.pack("<I", window)))
                    count = struct.unpack_from("<H", reply, 16)[0]
                    return struct.unpack_from("<" + "I" * count, reply, 32)

                def error(code, sequence):
                    packet = c.packet()
                    assert packet[0] == 0 and packet[1] == code, packet.hex()
                    assert struct.unpack_from("<H", packet, 2)[0] == sequence
                    c.barrier()

                window = c.base | 1
                error(6, create(window, mask=(1 << 11) | (1 << 14), values=(3, c.base | 99)))
                assert children(c.root) == ()
                # Reuse proves both resource ownership and the tree rolled back.
                create(window)
                c.barrier()
                assert children(c.root) == (window,)
                # ChangeWindowAttributes carries a packed list in bit order.
                # The reply exposes fields from each independent state group.
                mask = sum(1 << bit for bit in (1, 4, 5, 6, 7, 8, 9, 10, 12))
                options = (0x102030, 2, 6, 1, 0x13579BDF, 0x010203, 1, 1, 3)
                c.send(2, payload=struct.pack("<II9I", window, mask, *options))
                def read_options():
                    return c.reply(c.send(3, payload=struct.pack("<I", window)))
                reply = read_options()
                assert reply[1] == 1 and reply[14:16] == bytes((2, 6)), reply.hex()
                assert struct.unpack_from("<II", reply, 16) == (0x13579BDF, 0x010203), reply.hex()
                assert reply[24] == 1 and reply[27] == 1, reply.hex()
                assert struct.unpack_from("<H", reply, 40)[0] == 3, reply.hex()
                error(6, c.send(2, payload=struct.pack("<IIII", window, (1 << 9) | (1 << 14),
                                                       0, c.base | 99)))
                assert read_options()[27] == 1
                c.send(4, payload=struct.pack("<I", window))
                c.barrier()
                assert children(c.root) == ()

                error(2, create(window, kind=3))
                error(8, create(window, kind=2, depth=24))
                error(8, create(window, kind=2, border=1))
                error(8, create(window, kind=2, mask=2, values=(0,)))
                assert children(c.root) == ()

                create(window, kind=2)
                c.barrier()
                geometry = c.reply(c.send(14, payload=struct.pack("<I", window)))
                assert geometry[1] == 0
                assert struct.unpack_from("<hhHHH", geometry, 12) == (-5, 3, 31, 17, 0)

                child_window = c.base | 2
                error(8, create(child_window, parent=window, kind=1))
                assert children(window) == ()
                create(child_window, parent=window, kind=0)
                c.barrier()
                assert children(window) == (child_window,)
                geometry = c.reply(c.send(14, payload=struct.pack("<I", child_window)))
                assert geometry[1] == 0
                assert struct.unpack_from("<hhHHH", geometry, 12) == (-5, 3, 31, 17, 0)
                c.send(4, payload=struct.pack("<I", window))
                c.barrier()
                assert children(c.root) == ()
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: window ID/listener rollback, reuse, class validation and InputOnly hierarchy/geometry")


if __name__ == "__main__":
    run(sys.argv[1])
