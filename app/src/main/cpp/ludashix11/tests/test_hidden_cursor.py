#!/usr/bin/env python3
"""Pointer input stays live without compositor redraws while the cursor is hidden."""
import os
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import time
from test_wire import Client


def run(binary):
    channel, child = socket.socketpair()
    with tempfile.TemporaryDirectory() as directory:
        path = pathlib.Path(directory) / 'log'
        with path.open('w+') as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
                env=dict(os.environ, LUDASHI_HOST_TRACE_SCENE='1', LUDASHI_HOST_CURSOR_HIDDEN='1', LUDASHI_HOST_INPUT='1'))
            child.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                c = Client(channel)
                win, pixmap = c.base | 1, c.base | 2
                c.send(1, payload=struct.pack('<IIhhHHHHII', win, c.root, 0, 0, 64, 64, 0, 1, 0, 0))
                c.send(8, payload=struct.pack('<I', win))
                c.send(53, 24, struct.pack('<IIHH', pixmap, win, 64, 64))
                present = c.extension('Present')
                c.send(present, 1, struct.pack('<IIIIIhhIIIIIQQQ', win, pixmap, 77, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                c.barrier()
                deadline = time.monotonic() + 3
                while 'HostScene serial=77 ' not in path.read_text():
                    assert time.monotonic() < deadline
                    time.sleep(.005)
                time.sleep(.05)
                count = path.read_text().count('HostScene')
                for n in range(32):
                    c.send(41, payload=struct.pack('<IIhhHHhh', 0, c.root, 0, 0, 0, 0, 400+n, 200+n))
                    c.barrier()
                    time.sleep(.003)
                pointer = c.reply(c.send(38, payload=struct.pack('<I', c.root)))
                assert struct.unpack_from('<hh', pointer, 16) == (431, 231)
                time.sleep(.05)
                assert path.read_text().count('HostScene') == count, path.read_text()
                process.stdin.write('cursor 1\n'); process.stdin.flush()
                assert process.stdout.readline().strip() == 'input'
                deadline = time.monotonic() + 3
                while 'cursor_x=416 cursor_y=216' not in path.read_text():
                    assert time.monotonic() < deadline, path.read_text()
                    time.sleep(.005)
                c.close()
                print('PASS: hidden cursor motion updates input without redraw; showing cursor uses latest coordinates')
            finally:
                process.terminate(); process.wait(10); channel.close()
                output = path.read_text()
                assert process.returncode == 0, output
                assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output


if __name__ == '__main__':
    run(sys.argv[1])
