#!/usr/bin/env python3
"""Validate drawable capabilities and RENDER backing lifetime without resource-tag casts."""
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
                render = c.extension("RENDER")
                reply = c.reply(c.send(render, 1))
                formats = {reply[32 + i * 28 + 5]: struct.unpack_from("<I", reply, 32 + i * 28)[0] for i in range(3)}
                window, input_only, pixmap, gc, picture, cursor = [c.base | i for i in range(1, 7)]
                for wid, kind in ((window, 1), (input_only, 2)):
                    c.send(1, 32 if kind == 1 else 0, struct.pack("<IIhhHHHHII", wid, c.root, 0, 0, 4, 4, 0, kind, 0, 0))
                c.send(53, 32, struct.pack("<IIHH", pixmap, window, 4, 4))
                c.send(55, payload=struct.pack("<IIII", gc, pixmap, 4, 0xff123456))
                c.send(70, payload=struct.pack("<IIhhHH", pixmap, gc, 0, 0, 4, 4))
                c.barrier()
                visual = struct.unpack_from("<I", c.reply(c.send(3, payload=struct.pack("<I", window))), 8)[0]
                def error(resource, format_id, code):
                    c.send(render, 4, struct.pack("<IIII", picture, resource, format_id, 0))
                    packet = c.packet()
                    assert packet[:2] == bytes((0, code)), packet.hex()
                error(gc, formats[32], 9)
                error(visual, formats[32], 9)
                error(input_only, formats[32], 8)
                error(window, formats[24], 8)
                c.send(render, 4, struct.pack("<IIII", picture, pixmap, formats[32], 0))
                c.send(54, payload=struct.pack("<I", pixmap))
                c.send(render, 27, struct.pack("<IIHH", cursor, picture, 0, 0))
                c.barrier()  # The picture retains its backing after the pixmap ID disappears.
                c.send(render, 7, struct.pack("<I", picture))
                c.send(95, payload=struct.pack("<I", cursor))
                c.send(render, 4, struct.pack("<IIII", picture, window, formats[32], 0))
                c.send(4, payload=struct.pack("<I", window))
                c.send(render, 27, struct.pack("<IIHH", cursor, picture, 0, 0))
                c.barrier()  # The same lifetime rule applies to window-backed pictures.
        finally:
            process.terminate(); process.wait(10)
            log.seek(0); output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: drawable capability errors, InputOnly/depth rejection, rejected picture ID reuse and pixmap/window backing retention")


if __name__ == "__main__": run(sys.argv[1])
