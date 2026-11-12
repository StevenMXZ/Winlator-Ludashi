#!/usr/bin/env python3
"""Exercise XI2 raw input, discovery, selection errors and warp semantics."""
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
    with tempfile.TemporaryFile(mode='w+') as log:
        process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
            env=dict(os.environ, LUDASHI_HOST_INPUT='1', LUDASHI_HOST_TRACE_SCENE='1'))
        child.close()
        try:
            assert process.stdout.readline().strip() == 'ready'
            c = Client(channel)
            xi = c.extension('XInputExtension')
            p = c.reply(c.send(xi, 47, struct.pack('<HH', 2, 3)))
            assert struct.unpack_from('<HH', p, 8) == (2, 2), p.hex()
            p = c.reply(c.send(xi, 48, struct.pack('<HH', 0, 0)))
            assert struct.unpack_from('<H', p, 8)[0] == 3, p.hex()
            offset = 32
            ids = []
            for _ in range(3):
                device, use, attachment, classes, namesize, enabled = struct.unpack_from('<HHHHHB', p, offset)
                ids.append(device)
                assert enabled == 1
                offset += 12 + ((namesize + 3) & ~3)
                for _ in range(classes):
                    kind, words, source = struct.unpack_from('<HHH', p, offset)
                    assert source == 4
                    assert (kind, words) in [(1, 10), (2, 11)]
                    offset += words * 4
            assert ids == [2, 3, 4] and offset == len(p), (ids, offset, len(p))
            c.send(xi, 48, struct.pack('<HH', 99, 0))
            p = c.packet(); assert p[0] == 0 and p[1] >= 128, p.hex()

            def select(device, mask, window=None):
                c.send(xi, 46, struct.pack('<IHHHHI', window or c.root, 1, 0, device, 1, mask))
                c.barrier()

            def inject(command):
                process.stdin.write(command + '\n'); process.stdin.flush()
                assert process.stdout.readline().strip() == 'input'

            def quiet():
                sequence = c.send(43)
                p = c.packet()
                assert p[0] == 1 and struct.unpack_from('<H', p, 2)[0] == sequence, p.hex()

            def motion(dx, dy):
                p = c.packet()
                assert len(p) == 68 and p[0] == 35 and p[1] == xi, p.hex()
                assert struct.unpack_from('<HH', p, 8) == (17, 2), p.hex()
                assert struct.unpack_from('<HH', p, 20) == (4, 1), p.hex()
                assert struct.unpack_from('<I', p, 32)[0] == 3, p.hex()
                values = []
                for offset in range(36, 68, 8):
                    integral, fraction = struct.unpack_from('<iI', p, offset)
                    values.append(integral + fraction / 4294967296)
                assert values == [dx, dy, dx, dy], values

            mask = (1 << 15) | (1 << 16) | (1 << 17)
            select(1, mask)
            select(0, mask)  # Two matching device masks must still yield one event.
            inject('relative 5 -3'); motion(5, -3); quiet()
            for pressed, kind in [(1, 15), (0, 16)]:
                inject('button 4 ' + str(pressed))
                p = c.packet()
                assert len(p) == 32 and struct.unpack_from('<H', p, 8)[0] == kind, p.hex()
                assert struct.unpack_from('<I', p, 16)[0] == 4, p.hex()
                p = c.reply(c.send(xi, 48, struct.pack('<HH', 2, 0)))
                assert struct.unpack_from('<I', p, 72)[0] == (1 << 4 if pressed else 0), p.hex()

            inject('anchor 100 100'); quiet()
            inject('absolute 110 95'); motion(10, -5)
            c.send(41, payload=struct.pack('<IIhhhhHH', 0, c.root, 0, 0, 0, 0, 0, 0))
            quiet()  # A Wine cursor warp must not generate raw motion.
            c.send(xi, 41, struct.pack('<IIiiHHiiHH', 0, c.root, 0, 0, 0, 0,
                                      200 << 16, 250 << 16, 2, 0))
            quiet()
            p = c.reply(c.send(38, payload=struct.pack('<I', c.root)))
            assert struct.unpack_from('<hh', p, 16) == (200, 250), p.hex()
            inject('absolute 120 90'); motion(10, -5)
            inject('raw 0 0'); inject('relative 4 7'); quiet()
            inject('raw 1 0'); inject('relative -4 -7'); motion(-4, -7)

            # Recover from overscroll without converting the correction into raw motion.
            for x, y, dx, dy, expected in [
                (-5000, 100, 20, 0, (4, 100)),
                (6000, 100, -20, 0, (1276, 100)),
                (100, -5000, 0, 20, (100, 4)),
                (100, 5000, 0, -20, (100, 716)),
            ]:
                inject('raw 0 0'); inject(f'absolute {x} {y}'); quiet()
                inject('raw 1 0'); inject(f'relative {dx} {dy}'); motion(dx, dy)
                p = c.reply(c.send(38, payload=struct.pack('<I', c.root)))
                assert struct.unpack_from('<hh', p, 16) == expected, p.hex()
            c.send(41, payload=struct.pack('<IIhhHHhh', 0, c.root, 0, 0, 0, 0, 200, 250)); quiet()
            inject('relative -7 9'); motion(-7, 9)
            p = c.reply(c.send(38, payload=struct.pack('<I', c.root)))
            assert struct.unpack_from('<hh', p, 16) == (193, 259), p.hex()

            # Reject an incomplete mask atomically; the preceding selections remain active.
            c.send(xi, 46, struct.pack('<IHHHH', c.root, 1, 0, 1, 10))
            p = c.packet(); assert p[0] == 0 and p[1] == 16, p.hex()
            inject('relative 1 2'); motion(1, 2)
            select(0, 0); select(1, 0)
            inject('relative 1 2'); quiet()

            # Visual pointer coordinates follow Java beyond the root, but explicit
            # XGrabPointer confinement still restricts the cursor to its window.
            pixmap, cursor, confine = [c.base | i for i in (10, 11, 12)]
            c.send(53, 1, struct.pack('<IIHH', pixmap, c.root, 1, 1))
            c.send(93, payload=struct.pack('<III8H', cursor, pixmap, 0, 0, 0, 0, 65535, 65535, 65535, 0, 0))
            c.send(2, payload=struct.pack('<III', c.root, 1 << 14, cursor)); c.barrier()
            def visual(x, y):
                expected = f'cursor_x={x} cursor_y={y}'
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    output = os.pread(log.fileno(), os.fstat(log.fileno()).st_size, 0).decode(errors='replace')
                    if expected in output: return
                    time.sleep(.005)
                raise AssertionError(expected)
            for x, y in [(-20, -30), (1300, -40), (-50, 750), (1400, 800), (20, 30)]:
                inject(f'absolute {x} {y}'); visual(x, y)
            c.send(1, payload=struct.pack('<IIhhHHHHII', confine, c.root, 40, 50, 100, 80, 0, 2, 0, 0))
            c.send(8, payload=struct.pack('<I', confine)); c.barrier()
            grabbed = c.reply(c.send(26, 1, struct.pack('<IHBBIII', c.root, 0, 1, 1, confine, 0, 0)))
            assert grabbed[1] == 0
            inject('absolute -100 1000'); visual(40, 129)
            c.send(27, payload=struct.pack('<I', 0)); c.barrier()
            inject('absolute -25 1005'); visual(-25, 1005)

            # Unimplemented reply requests produce an error rather than hanging.
            c.send(xi, 60, struct.pack('<I', c.root))
            p = c.packet(); assert p[0] == 0 and p[1] == 1, p.hex()
            select(1, mask); c.close()
            for _ in range(50):
                c = Client(channel); xi = c.extension('XInputExtension')
                select(1, mask); c.close(); inject('relative 1 1')
            print('PASS: XI2 devices, raw motion/buttons, fixed point, warps, offscreen cursor positions, explicit confinement, masks, errors and disconnected subscribers')
        finally:
            process.terminate(); process.wait(10); channel.close(); log.seek(0); output = log.read()
            assert process.returncode == 0, output
            assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output

if __name__ == '__main__': run(sys.argv[1])
