#!/usr/bin/env python3
"""Check discovery and negotiated versions against the Java server's extension catalog."""
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import tempfile

from test_wire import Client


def java_extensions():
    source = Path(__file__).resolve().parents[3] / "java/com/winlator/cmod/xserver"
    setup = re.search(r"private void setupExtensions\(\)\s*\{([^}]+)\}", (source / "XServer.java").read_text())
    classes = re.findall(r"new (\w+Extension)\(", setup[1])
    names = set()
    for name in classes:
        text = (source / "extensions" / (name + ".java")).read_text()
        names.add(re.search(r'getName\(\)\s*\{\s*return "([^"]+)"', text)[1])
    return names


def run(binary):
    channel, child = socket.socketpair()
    with channel, child, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            [binary, str(child.fileno())], pass_fds=(child.fileno(),),
            stdout=subprocess.PIPE, stderr=log, text=True,
        )
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            client = Client(channel)
            with client.sock:
                reply = client.reply(client.send(99))
                names, offset = [], 32
                for _ in range(reply[1]):
                    length = reply[offset]
                    offset += 1
                    names.append(reply[offset:offset + length].decode())
                    offset += length
                assert len(names) == len(set(names))
                assert set(names) == java_extensions() | {"RENDER"}, names
                opcodes = {name: client.extension(name) for name in names}
                assert len(set(opcodes.values())) == len(names)
                assert min(opcodes.values()) >= 128

                versions = {
                    "DRI3": (1, 2), "Present": (1, 2), "RANDR": (1, 2),
                    "GLX": (1, 4), "Composite": (0, 1), "RENDER": (0, 8),
                }
                for name, expected in versions.items():
                    reply = client.reply(client.send(
                        opcodes[name], 7 if name == "GLX" else 0,
                        struct.pack("<II", *expected),
                    ))
                    assert struct.unpack_from("<II", reply, 8) == expected, (name, reply.hex())

                reply = client.reply(client.send(opcodes["BIG-REQUESTS"]))
                assert struct.unpack_from("<I", reply, 8)[0] == 4194303
                reply = client.reply(client.send(opcodes["MIT-SHM"]))
                assert struct.unpack_from("<HH", reply, 8) == (1, 2)
                reply = client.reply(client.send(opcodes["SYNC"], payload=bytes((3, 1, 0, 0))))
                assert reply[8:10] == bytes((3, 1))
                reply = client.reply(client.send(opcodes["XInputExtension"], 47, struct.pack("<HH", 2, 2)))
                assert struct.unpack_from("<HH", reply, 8) == (2, 2)
                client.barrier()
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: all nine Java extensions plus RENDER, unique opcodes and version negotiation")


if __name__ == "__main__":
    run(sys.argv[1])
