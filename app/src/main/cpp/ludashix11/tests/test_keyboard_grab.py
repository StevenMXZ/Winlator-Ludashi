#!/usr/bin/env python3
"""Exercise active keyboard grabs through the X11 transport and host key injection."""
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time

from test_wire import Client


def run(binary):
    channel, child = socket.socketpair()
    clients = []
    with channel, child, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            [binary, str(child.fileno())], pass_fds=(child.fileno(),),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
            env=dict(os.environ, LUDASHI_HOST_INPUT="1"),
        )
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            owner, grabber, other = [Client(channel) for _ in range(3)]
            clients.extend((owner, grabber, other))
            focus, parent, capture, hidden = [owner.base | n for n in range(1, 5)]
            for wid, window, x in ((focus, owner.root, 0), (parent, owner.root, 100),
                                   (capture, parent, 0), (hidden, owner.root, 200)):
                owner.send(1, payload=struct.pack("<IIhhHHHHII", wid, window, x, 0, 64, 64, 0, 1, 0, 0))
                if wid != hidden:
                    owner.send(8, payload=struct.pack("<I", wid))
            owner.send(42, payload=struct.pack("<II", focus, 0))

            def mask(client, window, events):
                client.send(2, payload=struct.pack("<III", window, 1 << 11, events))
                client.barrier()

            def body(window, stamp=0, pointer_mode=1, keyboard_mode=1):
                return struct.pack("<IIBBxx", window, stamp, pointer_mode, keyboard_mode)

            def grab(client, window, expected, owner_events=0, stamp=0):
                seq = client.send(31, owner_events, body(window, stamp))
                assert client.reply(seq)[1] == expected

            def ungrab(client, stamp=0):
                client.send(32, payload=struct.pack("<I", stamp))
                client.barrier()

            def quiet(client):
                seq = client.send(43)
                packet = client.packet()
                assert packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == seq, packet.hex()
                return packet

            def error(client, opcode, payload, code, data=0):
                seq = client.send(opcode, data, payload)
                event = client.packet()
                assert event[:2] == bytes((0, code)), event.hex()
                assert struct.unpack_from("<H", event, 2)[0] == seq

            def inject(down):
                process.stdin.write(f"key 38 {int(down)}\n")
                process.stdin.flush()
                assert process.stdout.readline().strip() == "input"

            def key_event(client, window, down):
                event = client.packet()
                assert event[:2] == bytes((2 if down else 3, 38)), event.hex()
                assert struct.unpack_from("<I", event, 12)[0] == window, event.hex()

            def focus_event(client, window, kind, mode):
                event = client.packet()
                assert event[0] == kind and event[8] == mode, event.hex()
                assert struct.unpack_from("<I", event, 4)[0] == window, event.hex()

            mask(owner, focus, 3 | (1 << 21))
            mask(other, capture, 1 << 21)
            # Obtain an older timestamp from normal delivery before the grab.
            inject(True)
            event = owner.packet()
            assert event[:2] == bytes((2, 38)), event.hex()
            older = struct.unpack_from("<I", event, 4)[0]
            inject(False)
            key_event(owner, focus, False)
            time.sleep(.01)
            grab(grabber, hidden, 3)
            grab(grabber, capture, 2, stamp=older + 100000)
            error(grabber, 31, body(owner.base | 99), 3)
            error(grabber, 31, body(capture), 2, data=2)
            error(grabber, 31, body(capture, keyboard_mode=2), 2)
            error(grabber, 32, b"", 16)

            grab(grabber, capture, 0)
            focus_event(owner, focus, 10, 1)
            focus_event(other, capture, 9, 1)
            assert struct.unpack_from("<I", quiet(grabber), 8)[0] == focus
            grab(other, capture, 1)
            ungrab(other)
            ungrab(grabber, stamp=older)
            for down in (True, False):
                inject(down)
                key_event(grabber, capture, down)
                quiet(owner)

            mask(grabber, focus, 3)
            grab(grabber, capture, 0, owner_events=1)
            for down in (True, False):
                inject(down)
                key_event(grabber, focus, down)
                quiet(owner)  # owner_events still delivers only to the grabbing client.
            mask(grabber, focus, 0)
            for down in (True, False):
                inject(down)
                key_event(grabber, capture, down)
                quiet(owner)
            ungrab(grabber)
            focus_event(other, capture, 10, 2)
            focus_event(owner, focus, 9, 2)

            grab(grabber, capture, 0)
            focus_event(owner, focus, 10, 1)
            focus_event(other, capture, 9, 1)
            owner.send(10, payload=struct.pack("<I", parent))
            owner.barrier()  # Unmapping the ancestor must release the capture.
            grab(other, capture, 3)
            for down in (True, False):
                inject(down)
                key_event(owner, focus, down)
                quiet(grabber)
            owner.send(8, payload=struct.pack("<I", parent))
            owner.barrier()
            grab(grabber, capture, 0)
            focus_event(other, capture, 9, 1)
            grabber.close()  # Capture points to another client's still-live window.
            focus_event(other, capture, 10, 2)
            grab(other, capture, 0)
            grab(owner, capture, 1)
            owner.send(4, payload=struct.pack("<I", capture))
            owner.barrier()
            grab(owner, focus, 0)
            ungrab(owner)
            for down in (True, False):
                inject(down)
                key_event(owner, focus, down)
        finally:
            for client in clients:
                client.close()
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: active keyboard grabs, owner_events, focus reports, timestamps, validation and automatic release")


if __name__ == "__main__":
    run(sys.argv[1])
