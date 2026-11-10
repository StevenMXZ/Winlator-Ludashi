#!/usr/bin/env python3
"""Virtual RandR modes must mirror the panel list, independently of FPS pacing."""
import os, pathlib, socket, struct, subprocess, sys, tempfile, time
from test_wire import Client

def run(binary, diagnostics):
    with tempfile.TemporaryDirectory(prefix='ludashi-randr-') as directory:
        channel, child = socket.socketpair()
        logpath = pathlib.Path(directory) / 'server.log'
        with open(logpath, 'w+') as log:
            env = dict(os.environ, LUDASHI_HOST_RANDR_RATES='60,120,90,120,0',
                       WINLATOR_X11='1' if diagnostics else '0')
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True, env=env)
            child.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                c = Client(channel); rr = c.extension('RANDR')
                def screen(): return c.reply(c.send(rr, 5, struct.pack('<I', c.root)))
                p = screen()
                assert struct.unpack_from('<HH', p, 26) == (120, 4), p.hex()
                assert struct.unpack_from('<4H', p, 40) == (3, 120, 90, 60), p.hex()
                resources = c.reply(c.send(rr, 8, struct.pack('<I', c.root)))
                assert struct.unpack_from('<HHH', resources, 16) == (1, 1, 3), resources.hex()
                crtc, output = struct.unpack_from('<II', resources, 32)
                modes = []
                for i, hz in enumerate((120, 90, 60)):
                    mode, w, h, clock = struct.unpack_from('<IHHI', resources, 40 + i * 32)
                    assert (w, h, clock) == (1280, 720, 1280 * 720 * hz)
                    modes.append(mode)
                info = c.reply(c.send(rr, 9, struct.pack('<II', output, 1)))
                assert struct.unpack_from('<HHH', info, 26) == (1, 3, 1), info.hex()
                assert struct.unpack_from('<3I', info, 40) == tuple(modes), info.hex()
                def current_mode():
                    p = c.reply(c.send(rr, 20, struct.pack('<II', crtc, 1)))
                    return struct.unpack_from('<I', p, 20)[0]
                assert current_mode() == modes[0]
                # RR 1.1 selects 60 Hz; RR 1.2 sees the same selection.
                p = c.reply(c.send(rr, 2, struct.pack('<IIIHHHH', c.root, 0, 0, 0, 1, 60, 0)))
                assert p[1] == 0 and current_mode() == modes[2]
                # CRTC selection restores the preferred 120 Hz virtual mode.
                p = c.reply(c.send(rr, 21, struct.pack('<IIIhhIHHI', crtc, 0, 0, 0, 0, modes[0], 1, 0, output)))
                assert p[1] == 0 and struct.unpack_from('<H', screen(), 26)[0] == 120
                c.send(126); assert c.packet()[0] == 0; c.barrier()
                if diagnostics:
                    # Trigger an active sampling window long enough to report metrics.
                    win, gc = c.base | 1, c.base | 2
                    c.send(1, payload=struct.pack('<IIhhHHHHII', win, c.root, 0, 0, 64, 64, 0, 1, 0, 0))
                    c.send(8, payload=struct.pack('<I', win))
                    c.send(55, payload=struct.pack('<III', gc, win, 0)); c.barrier()
                    time.sleep(1.1)
                    c.send(70, payload=struct.pack('<IIhhHH', win, gc, 0, 0, 64, 64)); c.barrier()
                    deadline = time.monotonic() + 2
                    while 'virtual_hz=120' not in logpath.read_text() and time.monotonic() < deadline:
                        time.sleep(.01)
                    text = logpath.read_text()
                    assert 'X11ProtocolError' in text and 'virtual_hz=120 panel_hz=60.0 limiter=0' in text, text
                else:
                    assert 'X11Metrics' not in logpath.read_text()
                c.close()
            finally:
                process.terminate()
                try: process.wait(10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(); raise
                channel.close(); log.seek(0); text = log.read()
                assert process.returncode == 0, text
                assert 'ERROR: AddressSanitizer' not in text and 'runtime error:' not in text, text
    print(f'PASS: RandR 60/90/120 legacy/resources/CRTC selection; diagnostics={diagnostics}')

if __name__ == '__main__':
    run(sys.argv[1], False)
    run(sys.argv[1], True)
