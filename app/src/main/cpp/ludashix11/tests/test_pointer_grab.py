#!/usr/bin/env python3
"""Verify active pointer grab ownership, viewability, time ordering and failed-request isolation."""
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
            a, b = Client(channel), Client(channel)
            with a.sock, b.sock:
                parent, nested, hidden = [a.base | i for i in range(1, 4)]
                def create(window, above, mapped=True):
                    a.send(1, payload=struct.pack("<IIhhHHHHII", window, above, 0, 0, 32, 32, 0, 1, 0, 0))
                    if mapped: a.send(8, payload=struct.pack("<I", window))
                def request(client, window, owner=0, mask=0, confined=0, cursor=0, timestamp=0, pmode=1, kmode=1):
                    return client.send(26, owner, struct.pack("<IHBBIII", window, mask, pmode, kmode,
                                                            confined, cursor, timestamp))
                def grab(client, window, status, **kwargs):
                    reply = client.reply(request(client, window, **kwargs))
                    assert reply[1] == status, reply.hex()
                def release(client, timestamp=0):
                    client.send(27, payload=struct.pack("<I", timestamp))
                    client.barrier()
                def error(code, value, **kwargs):
                    request(a, parent, **kwargs)
                    packet = a.packet()
                    assert packet[:2] == bytes((0, code)), packet.hex()
                    assert struct.unpack_from("<I", packet, 4)[0] == value, packet.hex()
                    a.barrier()
                create(parent, a.root)
                create(nested, parent)
                create(hidden, a.root, False)
                a.barrier()
                grab(a, hidden, 3)
                grab(a, parent, 3, confined=hidden)
                grab(b, b.root, 0)
                release(b)

                a.send(2, payload=struct.pack("<III", hidden, 1 << 11, 1 << 22))
                name = b"_LUDASHI_GRAB_CLOCK"
                reply = a.reply(a.send(16, payload=struct.pack("<HH", len(name), 0) + name + b"\0" * (-len(name) % 4)))
                atom = struct.unpack_from("<I", reply, 8)[0]
                a.send(18, payload=struct.pack("<IIIB3xI", hidden, atom, 31, 8, 1) + b"a\0\0\0")
                packet = a.packet()
                assert packet[0] == 28
                old = struct.unpack_from("<I", packet, 12)[0]
                future = (old + 1000000) & 0xffffffff
                time.sleep(.01)
                grab(a, nested, 0)
                grab(b, b.root, 1)
                grab(a, parent, 0)  # The current owner can replace its own grab.
                release(a, old)
                grab(b, b.root, 1)
                release(a, future)
                grab(b, b.root, 1)
                release(b)  # Another client cannot cancel it.
                grab(b, b.root, 1)

                for kwargs, code, value in [
                    ({"owner": 2}, 2, 2), ({"mask": 1}, 2, 1),
                    ({"pmode": 2}, 2, 2), ({"kmode": 3}, 2, 3),
                    ({"confined": a.base | 99}, 3, a.base | 99),
                    ({"cursor": a.base | 99}, 6, a.base | 99),
                ]:
                    error(code, value, **kwargs)
                    grab(b, b.root, 1)  # Rejected requests leave the active grab intact.
                release(a)
                grab(b, b.root, 0)
                release(b)
                grab(a, parent, 2, timestamp=old)
                grab(a, parent, 2, timestamp=future)
                grab(b, b.root, 0)
                release(b)

                grab(a, nested, 0)
                a.send(10, payload=struct.pack("<I", parent))
                a.barrier()
                grab(b, b.root, 0)  # Hiding an ancestor releases the grab.
                release(b)
                grab(a, nested, 3)
                grab(a, a.root, 3, confined=nested)
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: pointer grab ownership/replacement, visibility/ancestors, timestamps, invalid requests, failure isolation and automatic release")


if __name__ == "__main__":
    run(sys.argv[1])
