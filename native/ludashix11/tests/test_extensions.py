#!/usr/bin/env python3
"""Composite parity, configure/grab events and libXcursor's RENDER wire path."""
import os, pathlib, re, socket, struct, subprocess, sys, tempfile, time
from test_wire import Client

def run(binary):
    drag = subprocess.run([binary, '--pointer-drag'], capture_output=True, text=True)
    assert drag.returncode == 0, drag.stdout + drag.stderr
    print(drag.stdout.strip())
    fences = subprocess.run([binary, '--fence-hotpath'], capture_output=True, text=True)
    assert fences.returncode == 0, fences.stdout + fences.stderr
    print(fences.stdout.strip())
    with tempfile.TemporaryDirectory(prefix='ludashi-extensions-') as directory:
        channel, child = socket.socketpair()
        logpath = pathlib.Path(directory) / 'server.log'
        with logpath.open('w+') as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True,
                env=dict(os.environ, LUDASHI_HOST_TRACE_SCENE='1', WINLATOR_X11='1'))
            child.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                c, other = Client(channel), Client(channel)
                composite, render = c.extension('Composite'), c.extension('RENDER')
                name = b'RENDER'
                p = c.reply(c.send(98, payload=struct.pack('<HH', len(name), 0) + name + bytes(2)))
                render_error = p[11]
                p = c.reply(c.send(composite, 0, struct.pack('<II', 0, 4)))
                assert struct.unpack_from('<II', p, 8) == (0, 1)
                p = c.reply(c.send(render, 0, struct.pack('<II', 0, 11)))
                assert struct.unpack_from('<II', p, 8) == (0, 8)
                p = c.reply(c.send(render, 1))
                assert len(p) == 160 and struct.unpack_from('<5I', p, 8) == (3, 1, 3, 1, 1)
                formats = {p[32 + i*28 + 5]:struct.unpack_from('<I', p, 32 + i*28)[0] for i in range(3)}
                src, dst, gc = [c.base | i for i in (1, 2, 3)]
                def window(w, x, y, depth=24, parent=None):
                    c.send(1, depth, struct.pack('<IIhhHHHHII', w, parent or c.root, x, y, 32, 32, 0, 1, 0, 0))
                    c.send(8, payload=struct.pack('<I', w))
                window(src, 10, 20); window(dst, 100, 200)
                c.send(55, payload=struct.pack('<III', gc, src, 0))
                def fill(drawable, color):
                    c.send(56, payload=struct.pack('<III', gc, 4, color))
                    c.send(70, payload=struct.pack('<IIhhHH', drawable, gc, 0, 0, 32, 32))
                def pixel(drawable):
                    p = c.reply(c.send(73, 2, struct.pack('<IhhHHI', drawable, 0, 0, 1, 1, 0xffffffff)))
                    return struct.unpack_from('<I', p, 32)[0] & 0xffffff
                def copy(gc_id=gc):
                    c.send(62, payload=struct.pack('<III6h', src, dst, gc_id, 0, 0, 8, 9, 1, 1))
                def error(client, opcode, minor, payload, expected):
                    seq = client.send(opcode, minor, payload); p = client.packet()
                    assert p[0] == 0 and p[1] == expected and struct.unpack_from('<H', p, 2)[0] == seq, p.hex()
                    client.barrier()
                fill(src, 0xff0000); fill(dst, 0x0000ff); c.barrier()
                c.send(composite, 1, struct.pack('<IB3x', src, 1)); c.barrier()
                error(other, composite, 1, struct.pack('<IB3x', src, 1), 10)
                other.send(composite, 1, struct.pack('<IB3x', src, 0)); other.barrier()
                copy(0); c.barrier() # Java's redirect path also bypasses the GC copy.
                p = c.reply(c.send(14, payload=struct.pack('<I', src)))
                assert struct.unpack_from('<hh', p, 12) == (108, 209), p.hex()
                assert pixel(dst) == 0x0000ff
                p = c.reply(c.send(15, payload=struct.pack('<I', c.root)))
                children = struct.unpack_from('<' + 'I' * ((len(p)-32)//4), p, 32)
                assert children.index(src) > children.index(dst), children
                # No movement/stack change for repeated redirected CopyArea.
                for _ in range(128): copy(0)
                c.barrier(); assert pixel(dst) == 0x0000ff
                # Resize must preserve redirect state, independently of content replacement.
                c.send(12, payload=struct.pack('<IHHII', src, 12, 0, 40, 40)); copy(0); c.barrier()
                c.send(composite, 3, struct.pack('<IB3x', src, 1)); c.barrier()
                fill(src, 0xff0000); copy(0); c.barrier() # Other client's automatic redirect remains.
                other.close(); time.sleep(.03); c.barrier()
                copy(); c.barrier()
                # Copy target is (8,9), not (0,0).
                p = c.reply(c.send(73, 2, struct.pack('<IhhHHI', dst, 8, 9, 1, 1, 0xffffffff)))
                assert struct.unpack_from('<I', p, 32)[0] & 0xffffff == 0xff0000
                error(c, composite, 1, struct.pack('<IB3x', src, 2), 2)
                error(c, composite, 1, struct.pack('<IB3x', c.root, 0), 8)
                error(c, composite, 6, struct.pack('<II', src, c.base | 77), 17)
                # Destroy and reuse an ID: redirect state must not survive.
                c.send(composite, 1, struct.pack('<IB3x', src, 0)); c.send(4, payload=struct.pack('<I', src))
                window(src, 30, 40); fill(src, 0x00ff00); copy(); c.barrier()
                # ConfigureNotify.event identifies recipient; .window always identifies the moved window.
                c.send(2, payload=struct.pack('<III', src, 1 << 11, 1 << 17))
                c.send(12, payload=struct.pack('<IHHII', src, 3, 0, 50, 60))
                seq = c.send(43); events = []
                while True:
                    p = c.packet()
                    assert p[0] != 0, p.hex()
                    if p[0] == 1 and struct.unpack_from('<H', p, 2)[0] == seq: break
                    if p[0] == 22: events.append(p)
                assert len(events) == 1 and struct.unpack_from('<II', events[0], 4) == (src, src), [p.hex() for p in events]
                assert struct.unpack_from('<hh', events[0], 16) == (50, 60)
                # Normal routing and owner_events fallback through real XGrabPointer.
                c.send(2, payload=struct.pack('<III', src, 1 << 11, 1 << 6)); c.barrier()
                p = c.reply(c.send(26, 1, struct.pack('<IHBBIII', src, 1 << 6, 1, 1, 0, 0, 0)))
                assert p[1] == 0
                c.send(41, payload=struct.pack('<IIhhHHhh', 0, c.root, 0, 0, 0, 0, 500, 550))
                p = c.packet(); assert p[0] == 6 and struct.unpack_from('<I', p, 12)[0] == src, p.hex()
                c.send(27, payload=struct.pack('<I', 0)); c.barrier()
                wm = Client(channel)
                wm.send(2, payload=struct.pack('<III', c.root, 1 << 11, 1 << 20)); wm.barrier()
                c.send(12, payload=struct.pack('<IHHII', src, 3, 0, 70, 80)); c.barrier()
                p = wm.packet(); assert p[0] == 23 and struct.unpack_from('<I', p, 8)[0] == src, p.hex()
                p = c.reply(c.send(14, payload=struct.pack('<I', src)))
                assert struct.unpack_from('<hh', p, 12) == (50, 60)
                wm.send(12, payload=struct.pack('<IHHII', src, 3, 0, 70, 80)); wm.barrier()
                p = c.reply(c.send(14, payload=struct.pack('<I', src)))
                assert struct.unpack_from('<hh', p, 12) == (70, 80)
                # CompositeCopy is server-side composition, not a second WM configure request.
                c.send(composite, 1, struct.pack('<IB3x', src, 0)); copy(0); c.barrier()
                p = c.reply(c.send(14, payload=struct.pack('<I', src)))
                assert struct.unpack_from('<hh', p, 12) == (108, 209)
                c.send(composite, 3, struct.pack('<IB3x', src, 0)); c.barrier()
                wm.close(); c.barrier()
                # libXcursor: upload ARGB, CreatePicture, FreePixmap, CreateCursor, FreePicture.
                pix, picture, cursor, animated = [c.base | i for i in (10, 11, 12, 13)]
                c.send(53, 32, struct.pack('<IIHH', pix, c.root, 3, 2))
                colors = (0xffff0000, 0xff00ff00, 0xff0000ff, 0x80008000, 0, 0xff0000ff)
                c.send(72, 2, struct.pack('<IIHHhhBBH', pix, gc, 3, 2, 0, 0, 0, 32, 0) + struct.pack('<6I', *colors))
                c.send(render, 4, struct.pack('<IIII', picture, pix, formats[32], 0))
                c.send(54, payload=struct.pack('<I', pix))
                c.send(render, 27, struct.pack('<IIHH', cursor, picture, 1, 1))
                c.send(render, 7, struct.pack('<I', picture))
                c.send(render, 31, struct.pack('<III', animated, cursor, 16))
                c.send(2, payload=struct.pack('<III', c.root, 1 << 14, animated)); c.barrier()
                deadline = time.monotonic() + 2
                pattern = r'HostCursor w=3 h=2 first=4294901760 row1=2147548928 last=4278190335'
                while not re.search(pattern, logpath.read_text()) and time.monotonic() < deadline: time.sleep(.005)
                assert re.search(pattern, logpath.read_text()), logpath.read_text()
                c.send(95, payload=struct.pack('<I', animated))
                c.send(95, payload=struct.pack('<I', cursor)); c.barrier()
                # FreeCursor releases the ID while root/grab references keep its image alive.
                error(c, 96, 0, struct.pack('<I6H', animated, *([0] * 6)), 6)
                c.send(53, 32, struct.pack('<IIHH', pix, c.root, 3, 2))
                c.send(render, 4, struct.pack('<IIII', picture, pix, formats[32], 0))
                c.send(render, 27, struct.pack('<IIHH', cursor, picture, 0, 0))
                c.send(2, payload=struct.pack('<III', c.root, 1 << 14, cursor)); c.barrier()
                deadline = time.monotonic() + 2
                while 'HostCursor w=3 h=2 first=0 row1=0 last=0' not in logpath.read_text() and time.monotonic() < deadline: time.sleep(.005)
                assert 'HostCursor w=3 h=2 first=0 row1=0 last=0' in logpath.read_text(), logpath.read_text()
                c.send(render, 7, struct.pack('<I', picture)); c.send(54, payload=struct.pack('<I', pix))
                c.send(95, payload=struct.pack('<I', cursor)); c.barrier()
                # Invalid requests produce wire errors without disconnecting the game.
                error(c, render, 27, struct.pack('<IIHH', c.base | 90, picture, 0, 0), render_error + 1)
                error(c, render, 8, bytes(32), 17)
                c.close()
                # Pointer updates on later connections may still use the freed root cursor.
                later = Client(channel)
                later.send(41, payload=struct.pack('<IIhhHHhh', 0, later.root, 0, 0, 0, 0, 600, 650)); later.barrier(); later.close()
                print('PASS: Composite redirects, ownership, teardown, resize, stacking, no-copy; configure/grab events; RENDER formats, ARGB alpha/colors and freed-pixmap lifetime')
            finally:
                process.terminate(); process.wait(10); channel.close()
                log.seek(0); output = log.read()
                assert process.returncode == 0, output
                assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output

if __name__ == '__main__': run(sys.argv[1])
