#!/usr/bin/env python3
"""Bulk window requests preserve parents, stacking, mapping bits and per-client ownership."""
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
        clients = []
        try:
            assert process.stdout.readline().strip() == "ready"
            a, b, observer = [Client(channel) for _ in range(3)]
            clients += [a, b, observer]
            parent, first, second, nested, helper = a.base | 1, a.base | 2, b.base | 1, b.base | 2, a.base | 3
            def create(client, window, above, kind=1):
                client.send(1, payload=struct.pack("<IIhhHHHHII", window, above, 0, 0, 32, 32, 0, kind, 0, 0))
                client.barrier()
            def children(window):
                reply = a.reply(a.send(15, payload=struct.pack("<I", window)))
                count = struct.unpack_from("<H", reply, 16)[0]
                return struct.unpack_from("<" + "I" * count, reply, 32)
            def state(window):
                return a.reply(a.send(3, payload=struct.pack("<I", window)))[26]
            def bulk(client, opcode, window):
                client.send(opcode, payload=struct.pack("<I", window))
                client.barrier()
            def events():
                sequence = observer.send(43)
                result = []
                while True:
                    packet = observer.packet()
                    assert packet[0] != 0, packet.hex()
                    if packet[0] == 1:
                        assert struct.unpack_from("<H", packet, 2)[0] == sequence
                        return result
                    result.append((packet[0], struct.unpack_from("<I", packet, 8)[0]))
            def select(mask):
                observer.send(2, payload=struct.pack("<III", parent, 1 << 11, mask))
                observer.barrier()
            create(a, parent, a.root)
            create(a, first, parent)
            create(b, nested, first)
            create(b, second, parent)
            create(a, helper, parent, 2)
            select(1 << 19)
            bulk(a, 9, parent)
            assert events() == [(19, helper), (19, second), (19, first)]
            assert state(parent) == 0 and state(nested) == 0
            assert [state(window) for window in (first, second, helper)] == [1, 1, 1]
            a.send(8, payload=struct.pack("<I", parent))
            b.send(8, payload=struct.pack("<I", nested))
            b.barrier(); a.barrier()
            bulk(a, 11, parent)
            assert events() == [(18, first), (18, second), (18, helper)]
            assert state(parent) == 2 and state(nested) == 1
            assert children(parent) == (first, second, helper)
            bulk(a, 11, parent)
            assert events() == []
            select((1 << 19) | (1 << 20))
            a.send(2, payload=struct.pack("<III", helper, 1 << 9, 1))
            bulk(a, 9, parent)
            assert events() == [(19, helper), (20, second), (20, first)]
            assert state(first) == 0 and state(helper) == 2
            observer.send(9, payload=struct.pack("<I", parent))  # The redirect owner may map its own windows.
            assert events() == [(19, second), (19, first)]
            assert state(nested) == 2  # Its mapped bit survived hiding its parent.
            for opcode in (5, 9, 11):
                for payload, code in ((b"", 16), (struct.pack("<II", parent, 0), 16),
                                      (struct.pack("<I", a.base | 999), 3)):
                    a.send(opcode, payload=payload)
                    packet = a.packet()
                    assert packet[:2] == bytes((0, code)), packet.hex()
                assert children(parent) == (first, second, helper)
            bulk(b, 5, parent)
            destroyed = [item for item in events() if item[0] == 17]
            assert destroyed == [(17, first), (17, second), (17, helper)], destroyed
            assert children(parent) == () and state(parent) == 2
            assert children(a.root) == (parent,)
            create(b, second, a.root)
            create(b, nested, second)  # Foreign resource IDs were removed from their actual owner.
            bulk(a, 5, parent)  # Empty subtree is a no-op.
            bulk(a, 5, a.root)
            assert children(a.root) == ()
        finally:
            for client in clients: client.close()
            process.terminate(); process.wait(10)
            log.seek(0); output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: Map/Unmap/DestroySubwindows ordering, direct-child scope, mapped bits, redirects, parent/root survival, validation and mixed-owner ID reuse")


if __name__ == "__main__": run(sys.argv[1])
