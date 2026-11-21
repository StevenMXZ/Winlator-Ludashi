#!/usr/bin/env python3
"""Verify Java keymap compatibility, paired modifiers, key state and duplicate suppression."""
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import tempfile

from test_wire import Client


def run(binary):
    channel, child = socket.socketpair()
    with channel, child, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            [binary, str(child.fileno())], pass_fds=(child.fileno(),),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
            env=dict(os.environ, LUDASHI_HOST_INPUT="1"),
        )
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            c = Client(channel)
            with c.sock:
                def key(code, pressed):
                    process.stdin.write(f"key {code} {int(pressed)}\n")
                    process.stdin.flush()
                    assert process.stdout.readline().strip() == "input"

                def state():
                    reply = c.reply(c.send(38, payload=struct.pack("<I", c.root)))
                    return struct.unpack_from("<H", reply, 24)[0]

                def keymap():
                    reply = c.reply(c.send(44))
                    assert len(reply) == 40
                    return reply[8:40]

                source = Path(__file__).resolve().parents[3] / "java/com/winlator/cmod/xserver"
                codes = dict((name, int(code)) for name, code in re.findall(
                    r"\b(KEY_\w+)\((\d+)\)", (source / "XKeycode.java").read_text()))
                symbols = re.findall(r"keyboard.setKeysyms\(XKeycode.(KEY_\w+).id, (\d+), (\d+)\)",
                                     (source / "Keyboard.java").read_text())
                reply = c.reply(c.send(101, payload=struct.pack("<BBH", 8, 248, 0)))
                mapping = struct.unpack_from("<496I", reply, 32)
                for name, normal, shifted in symbols:
                    expected = ({"KEY_ALT_L": 65513, "KEY_ALT_R": 65514}.get(name, int(normal)), int(shifted))
                    offset = (codes[name] - 8) * 2
                    assert mapping[offset:offset + 2] == expected, name
                assert mapping[(codes["KEY_KP_ENTER"] - 8) * 2] == 65421

                for left, right, mask in [(50, 62, 1), (37, 105, 4), (64, 108, 8)]:
                    key(left, True)
                    key(right, True)
                    assert state() & mask
                    key(left, False)
                    assert state() & mask
                    bits = keymap()
                    assert not bits[left // 8] & (1 << (left % 8))
                    assert bits[right // 8] & (1 << (right % 8))
                    key(right, False)
                    assert not state() & mask

                for code in (8, 38, 66, 77, 255):
                    key(code, True)
                    bits = keymap()
                    assert bits[code // 8] & (1 << (code % 8)), code
                    key(code, False)
                for code in (0, 7, 256, 999):
                    key(code, True)
                assert keymap() == bytes(32)

                window = c.base | 1
                c.send(1, payload=struct.pack("<IIhhHHHHIII", window, c.root, 0, 0, 32, 32, 0, 1, 0, 1 << 11, 3))
                c.send(8, payload=struct.pack("<I", window))
                c.send(42, payload=struct.pack("<II", window, 0))
                c.barrier()
                for pressed, event in [(True, 2), (False, 3)]:
                    key(38, pressed)
                    assert c.packet()[0] == event
                    key(38, pressed)
                    sequence = c.send(43)
                    reply = c.packet()
                    assert reply[0] == 1 and struct.unpack_from("<H", reply, 2)[0] == sequence
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: Java symbol map, paired modifiers, QueryKeymap, invalid keys and duplicate events")


if __name__ == "__main__":
    run(sys.argv[1])
