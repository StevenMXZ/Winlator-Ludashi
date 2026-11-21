#!/usr/bin/env python3
"""Mixed-client subtree destruction, observer notifications, ID reuse and focus reversion."""
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
            owner, foreign, observer = [Client(channel) for _ in range(3)]
            clients += [owner, foreign, observer]
            parent, own_child = owner.base | 1, owner.base | 2
            other, nested = foreign.base | 1, foreign.base | 2
            def create(client, window, above):
                client.send(1, payload=struct.pack("<IIhhHHHHII", window, above, 10, 10, 32, 32, 0, 1, 0, 0))
                client.send(8, payload=struct.pack("<I", window))
            def select(client, window, mask):
                client.send(2, payload=struct.pack("<III", window, 1 << 11, mask))
            def drain(client):
                sequence = client.send(43)
                events = []
                while True:
                    packet = client.packet()
                    assert packet[0] != 0, packet.hex()
                    if packet[0] == 1:
                        assert struct.unpack_from("<H", packet, 2)[0] == sequence
                        return events
                    events.append(packet)
            def destroyed(events):
                return [struct.unpack_from("<II", event, 4) for event in events if event[0] == 17]
            def gone(client, window):
                client.send(15, payload=struct.pack("<I", window))
                packet = client.packet()
                assert packet[0:2] == b"\0\3", packet.hex()
            def setup():
                create(owner, parent, owner.root)
                create(owner, own_child, parent)
                owner.barrier()
                create(foreign, other, parent)
                create(foreign, nested, other)
                foreign.barrier()
                select(foreign, other, 1 << 17)
                select(foreign, nested, 1 << 17)
                select(observer, parent, (1 << 17) | (1 << 19))
                drain(observer)
                drain(foreign)
                reply = foreign.reply(foreign.send(26, 0, struct.pack("<IHBBIII", nested, 0, 1, 1, 0, 0, 0)))
                assert reply[1] == 0, reply.hex()
            def verify_ungrabbed():
                reply = observer.reply(observer.send(26, 0, struct.pack("<IHBBIII", observer.root, 0, 1, 1, 0, 0, 0)))
                assert reply[1] == 0, reply.hex()
                observer.send(27, payload=struct.pack("<I", 0))
                observer.barrier()
            select(observer, observer.root, 1 << 19)
            drain(observer)
            setup()

            # Removing one client must keep the other client's aggregate mask.
            structure, substructure, property_change = 1 << 17, 1 << 19, 1 << 22
            select(foreign, parent, property_change)
            foreign.barrier()
            reply = observer.reply(observer.send(3, payload=struct.pack("<I", parent)))
            assert struct.unpack_from("<II", reply, 32) == (
                structure | substructure | property_change, structure | substructure)
            select(foreign, parent, 0)
            foreign.barrier()
            reply = observer.reply(observer.send(3, payload=struct.pack("<I", parent)))
            assert struct.unpack_from("<II", reply, 32) == (structure | substructure, structure | substructure)

            # Hiding an ancestor makes the focused descendant unviewable.
            owner.send(42, 2, struct.pack("<II", own_child, 0))  # RevertToParent.
            owner.send(10, payload=struct.pack("<I", parent))
            reply = owner.reply(owner.send(43))
            assert struct.unpack_from("<I", reply, 8)[0] == owner.root, reply.hex()
            owner.send(8, payload=struct.pack("<I", parent))
            owner.barrier()
            drain(observer)
            drain(foreign)

            owner.send(4, payload=struct.pack("<I", owner.root))  # Root destruction is ignored.
            owner.send(4, payload=struct.pack("<I", parent))
            owner.barrier()
            assert destroyed(drain(foreign)) == [(nested, nested), (other, other)]
            assert destroyed(drain(observer)) == [
                (parent, own_child), (parent, other), (parent, parent), (owner.root, parent)]
            verify_ungrabbed()
            for window in (parent, own_child, other, nested):
                gone(foreign, window)

            # Reusing foreign IDs detects stale entries left in the other owner's table.
            create(foreign, other, foreign.root)
            create(foreign, nested, other)
            foreign.barrier()
            foreign.send(4, payload=struct.pack("<I", other))
            foreign.barrier()
            drain(observer)
            setup()
            owner.close()
            # Cross-client synchronization: wait until the disconnected parent's destruction is observed.
            events = []
            while True:
                event = observer.packet()
                events.append(event)
                if event[0] == 17 and struct.unpack_from("<II", event, 4) == (observer.root, parent): break
            assert destroyed(events) == [
                (parent, own_child), (parent, other), (parent, parent), (observer.root, parent)]
            assert destroyed(drain(foreign)) == [(nested, nested), (other, other)]
            verify_ungrabbed()
            for window in (other, nested):
                gone(foreign, window)
            create(foreign, other, foreign.root)
            create(foreign, nested, other)
            foreign.barrier()
            foreign.send(4, payload=struct.pack("<I", other))
            foreign.barrier()
            # No observer retains a pointer to the destroyed subtree during its own disconnect.
            observer.close()
            foreign.close()
        finally:
            for client in clients:
                client.close()
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: mixed-owner subtree destruction/disconnect, per-window DestroyNotify, foreign ID reuse, root safety and ancestor focus reversion")


if __name__ == "__main__":
    run(sys.argv[1])
