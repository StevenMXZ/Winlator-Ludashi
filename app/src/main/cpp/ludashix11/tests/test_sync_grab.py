#!/usr/bin/env python3
"""Check active core freezes, AllowEvents modes and queued input state over X11."""
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time

from test_wire import Client


def run(binary):
    path, channel = socket.socketpair()
    clients = []
    with path, channel, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            [binary, str(channel.fileno())], pass_fds=(channel.fileno(),),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
            env=dict(os.environ, LUDASHI_HOST_INPUT="1"),
        )
        channel.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            owner, app = [Client(path) for _ in range(2)]
            clients.extend((owner, app))
            window = app.base | 1
            app.send(1, payload=struct.pack("<IIhhHHHHII", window, app.root, 0, 0, 300, 300, 0, 1, 0, 0))
            app.send(8, payload=struct.pack("<I", window))
            app.send(42, payload=struct.pack("<II", window, 0))
            app.send(2, payload=struct.pack("<III", window, 1 << 11, 3 | (1 << 2) | (1 << 3) | (1 << 6)))
            app.barrier()

            def inject(*commands):
                for command in commands:
                    process.stdin.write(command + "\n")
                    process.stdin.flush()
                    assert process.stdout.readline().strip() == "input"

            def quiet(client):
                sequence = client.send(43)
                reply = client.packet()
                assert reply[0] == 1 and struct.unpack_from("<H", reply, 2)[0] == sequence, reply.hex()

            def event(client, kind, detail=None, position=None, state=None):
                msg = client.packet()
                assert msg[0] == kind and (detail is None or msg[1] == detail), msg.hex()
                if position is not None:
                    assert struct.unpack_from("<hh", msg, 20) == position, msg.hex()
                if state is not None:
                    assert struct.unpack_from("<H", msg, 28)[0] == state, msg.hex()
                return struct.unpack_from("<I", msg, 4)[0]

            def grab(client, device, pointer_mode, keyboard_mode, status=0, mask=76):
                if device == 26:
                    payload = struct.pack("<IHBBIII", window, mask, pointer_mode, keyboard_mode, 0, 0, 0)
                else:
                    payload = struct.pack("<IIBBxx", window, 0, pointer_mode, keyboard_mode)
                seq = client.send(device, payload=payload)
                assert client.reply(seq)[1] == status

            def allow(mode, client=owner, stamp=0):
                client.send(35, mode, struct.pack("<I", stamp))

            def ungrab(device, client=owner):
                client.send(device, payload=bytes(4))

            def state(position, buttons=0, shift=False):
                seq = app.send(38, payload=struct.pack("<I", app.root))
                msg = app.reply(seq)
                assert struct.unpack_from("<hh", msg, 16) == position, msg.hex()
                assert struct.unpack_from("<H", msg, 24)[0] == buttons | int(shift), msg.hex()
                keymap = app.reply(app.send(44))[8:40]
                assert bool(keymap[50 // 8] & (1 << (50 % 8))) == shift

            # Hold keyboard only. Replay is a no-op until a stepped event
            # actually reaches the owner and causes a new freeze.
            grab(owner, 31, 1, 0)
            inject("key 50 1", "key 50 0", "absolute 40 30")
            event(app, 6, position=(40, 30), state=0)
            state((40, 30))
            allow(5)
            quiet(owner)
            allow(3, app)
            quiet(app)
            allow(3, stamp=0x10000000)
            quiet(owner)
            time.sleep(.01)
            allow(4)
            press_time = event(owner, 2, 50, (40, 30), 0)
            quiet(owner)
            state((40, 30), shift=True)
            allow(5)
            assert event(app, 2, 50, (40, 30), 0) == press_time
            event(app, 3, 50, (40, 30), 1)
            quiet(owner)
            state((40, 30))

            grab(owner, 31, 1, 0)
            inject("key 38 1", "key 38 0")
            quiet(owner)
            allow(3)
            event(owner, 2, 38, (40, 30), 0)
            event(owner, 3, 38, (40, 30), 0)
            ungrab(32)
            quiet(owner)

            # Hold both from the pointer grab. Cross-device step must not
            # refreeze on an ordinary key event delivered outside a keyboard grab.
            grab(owner, 26, 0, 0)
            grab(app, 31, 1, 1, status=4)
            inject("absolute 80 50", "key 50 1", "button 1 1", "absolute 90 60",
                   "button 1 0", "key 50 0")
            state((40, 30))
            quiet(owner)
            allow(7)
            event(owner, 6, position=(80, 50), state=0)
            event(app, 2, 50, (80, 50), 0)
            event(owner, 4, 1, (80, 50), 1)
            quiet(owner)
            state((80, 50), 1 << 8, shift=True)
            allow(6)
            event(owner, 6, position=(90, 60), state=(1 << 8) | 1)
            event(owner, 5, 1, (90, 60), (1 << 8) | 1)
            event(app, 3, 50, (90, 60), 1)
            state((90, 60))

            # Freeze the same keyboard twice and thaw both contributions.
            grab(owner, 26, 1, 0)
            grab(owner, 31, 1, 0)
            inject("key 50 1", "key 50 0")
            allow(3)
            event(owner, 2, 50, (90, 60), 0)
            event(owner, 3, 50, (90, 60), 1)
            ungrab(32)
            ungrab(27)
            quiet(owner)

            # Step past a button release outside the selected mask and replay
            # the press once, with its original coordinates, timestamp and mask.
            grab(owner, 26, 1, 1, mask=68)
            inject("button 1 1")
            event(owner, 4, 1)
            grab(owner, 26, 0, 1, mask=68)
            inject("button 1 0", "absolute 91 61", "absolute 92 62", "button 1 1", "button 1 0")
            allow(1)
            event(owner, 6, position=(92, 62), state=0)
            stamp = event(owner, 4, 1, (92, 62), 0)
            quiet(owner)
            allow(2)
            assert event(app, 4, 1, (92, 62), 0) == stamp
            event(app, 5, 1, (92, 62), 1 << 8)
            quiet(owner)

            # A replay must wait if a second client's pointer grab freezes keys.
            grab(owner, 31, 1, 0)
            inject("key 50 1", "key 50 0")
            allow(4)
            press_time = event(owner, 2, 50, (92, 62), 0)
            grab(app, 26, 1, 0)
            allow(5)
            quiet(owner)
            quiet(app)
            ungrab(27, app)
            assert event(app, 2, 50, (92, 62), 0) == press_time
            event(app, 3, 50, (92, 62), 1)

            # The queue cap thaws an overflowing device and preserves key order.
            grab(owner, 31, 1, 0)
            inject(*("key 50 " + ("1" if i % 2 == 0 else "0") for i in range(4097)))
            for i in range(4097):
                event(owner, 2 if i % 2 == 0 else 3, 50, (92, 62), i % 2)
            state((92, 62), shift=True)
            inject("key 50 0")
            event(owner, 3, 50, (92, 62), 1)
            ungrab(32)
            quiet(owner)

            grab(owner, 26, 0, 1)
            grab(app, 31, 0, 1)
            grab(owner, 26, 1, 1, status=4)
            inject("absolute 96 66")
            allow(0, app)
            quiet(app)
            state((92, 62))
            ungrab(27)
            event(app, 6, position=(96, 66), state=0)
            ungrab(32, app)
            quiet(app)

            # A disconnect must drain queued input using surviving listeners.
            grab(owner, 26, 0, 0)
            inject("absolute 100 70", "key 50 1", "key 50 0")
            owner.close()
            event(app, 6, position=(100, 70), state=0)
            event(app, 2, 50, (100, 70), 0)
            event(app, 3, 50, (100, 70), 1)
            state((100, 70))
            seq = app.send(35, 8, struct.pack("<I", 0))
            msg = app.packet()
            assert msg[:2] == b"\x00\x02" and struct.unpack_from("<H", msg, 2)[0] == seq
        finally:
            for client in clients:
                client.close()
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: synchronous keyboard/pointer grabs, frozen queries, replay, step/thaw, overlapping grabs and disconnect")


if __name__ == "__main__":
    run(sys.argv[1])
