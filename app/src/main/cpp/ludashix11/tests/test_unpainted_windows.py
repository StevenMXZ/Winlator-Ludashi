#!/usr/bin/env python3
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import tempfile
import time
from test_wire import Client


def run(binary):
    with tempfile.TemporaryDirectory() as directory:
        channel, child = socket.socketpair()
        path = Path(directory) / 'scene.log'
        with path.open('w+') as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True,
                env=dict(os.environ, LUDASHI_HOST_TRACE_SCENE='1'))
            child.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                c = Client(channel)
                game, overlay, child_window, pixmap, child_pixmap, gc = [c.base | i for i in range(1, 7)]
                def window(w, parent, width, height):
                    c.send(1, payload=struct.pack('<IIhhHHHHII', w, parent, 0, 0, width, height, 0, 1, 0, 0))
                    c.send(8, payload=struct.pack('<I', w))
                window(game, c.root, 1280, 720)
                c.send(53, 24, struct.pack('<IIHH', pixmap, game, 1280, 720))
                present = c.extension('Present')
                def submit(w, image, serial, expected):
                    c.send(present, 1, struct.pack('<IIIIIhhIIIIIQQQ', w, image, serial, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                    c.barrier()
                    deadline = time.monotonic() + 3
                    pattern = rf'HostScene serial={serial} entries=(\d+) '
                    while time.monotonic() < deadline:
                        match = re.search(pattern, path.read_text())
                        if match:
                            assert int(match[1]) == expected, path.read_text()
                            return
                        time.sleep(.005)
                    raise AssertionError(path.read_text())
                submit(game, pixmap, 201, 1)
                window(overlay, c.root, 640, 360)
                submit(game, pixmap, 202, 1)
                window(child_window, overlay, 64, 64)
                c.send(53, 24, struct.pack('<IIHH', child_pixmap, child_window, 64, 64))
                submit(child_window, child_pixmap, 203, 2)
                c.send(55, payload=struct.pack('<III', gc, overlay, 4) + struct.pack('<I', 0))
                c.send(70, payload=struct.pack('<IIhhHH', overlay, gc, 0, 0, 640, 360))
                submit(game, pixmap, 204, 3)
                c.send(10, payload=struct.pack('<I', overlay))
                submit(game, pixmap, 205, 1)
                c.send(8, payload=struct.pack('<I', overlay))
                submit(game, pixmap, 206, 3)
                c.send(4, payload=struct.pack('<I', overlay)); c.barrier()
                window(overlay, c.root, 640, 360)
                submit(game, pixmap, 207, 1)
                c.send(2, payload=struct.pack('<III', overlay, 2, 0xff336699))
                submit(game, pixmap, 208, 2)
                c.send(4, payload=struct.pack('<I', overlay)); c.barrier()
                window(overlay, c.root, 640, 360)
                submit(game, pixmap, 209, 1)
                c.send(72, 2, struct.pack('<IIHHhhBB2x', overlay, gc, 1, 1, 0, 0, 0, 24) + bytes(4))
                submit(game, pixmap, 210, 2)
                c.close()
                print('PASS: unpainted foreground skipped; painted black window, direct child, remap and reused IDs compose correctly')
            finally:
                process.terminate(); process.wait(10); channel.close()
                assert process.returncode == 0, path.read_text()
                assert 'AddressSanitizer' not in path.read_text() and 'runtime error:' not in path.read_text()


if __name__ == '__main__':
    run(sys.argv[1])
