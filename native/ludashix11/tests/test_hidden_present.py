#!/usr/bin/env python3
"""Invisible producer frames acknowledge without walking or repainting the scene."""
import os, pathlib, re, socket, struct, subprocess, sys, tempfile, time
from test_wire import Client

def run(binary, occluded):
    with tempfile.TemporaryDirectory(prefix='ludashi-hidden-present-') as directory:
        channel, child = socket.socketpair(); logpath = pathlib.Path(directory) / 'server.log'
        with logpath.open('w+') as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True,
                env=dict(os.environ, LUDASHI_HOST_TRACE_SCENE='1', WINLATOR_X11='1'))
            child.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                c = Client(channel); present = c.extension('Present')
                back, front, small, large = [c.base | i for i in (1, 2, 3, 4)]
                for window in (back, front):
                    c.send(1, payload=struct.pack('<IIhhHHHHII', window, c.root, 0, 0, 64, 64, 0, 1, 0, 0))
                    if window == front or occluded: c.send(8, payload=struct.pack('<I', window))
                for pixmap, w, h in ((small, 64, 64), (large, 1280, 720)):
                    c.send(53, 24, struct.pack('<IIHH', pixmap, c.root, w, h))
                for i, window in enumerate((back, front)):
                    c.send(present, 3, struct.pack('<III', c.base | (10 + i), window, 0))
                    c.send(present, 3, struct.pack('<III', c.base | (10 + i), window, 6))
                c.barrier()
                def submit(window, pixmap, serial):
                    c.send(present, 1, struct.pack('<IIIIIhhIIIIIQQQ', window, pixmap, serial,
                           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                    events = [c.packet(), c.packet()]
                    assert [struct.unpack_from('<H', p, 8)[0] for p in events] == [2, 1]
                    assert all(p[0] == 35 and struct.unpack_from('<I', p, 20)[0] == serial for p in events)
                def report(previous):
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline:
                        matches = re.findall(r'scene_patch=(\d+) scene_build=(\d+).*?offscreen_present=(\d+)', logpath.read_text())
                        if len(matches) > previous: return matches
                        time.sleep(.005)
                    raise AssertionError(logpath.read_text())
                submit(back, small, 1); submit(front, large, 2)
                time.sleep(1.1); submit(front, large, 3)
                previous = len(report(0))
                for i in range(256): submit(back, small, i + 100)
                time.sleep(1.1); submit(front, large, 4)
                values = tuple(map(int, report(previous)[-1]))
                assert values == (1, 0, 256), values
                assert 'HostScene serial=355 ' not in logpath.read_text(), logpath.read_text()
                c.send(10, payload=struct.pack('<I', front))
                if not occluded: c.send(8, payload=struct.pack('<I', back))
                c.barrier()
                deadline = time.monotonic() + 2
                while 'HostScene serial=355 ' not in logpath.read_text() and time.monotonic() < deadline: time.sleep(.005)
                assert 'HostScene serial=355 ' in logpath.read_text(), logpath.read_text()
                c.close()
                print(f'PASS: 256 {"occluded" if occluded else "unmapped"} Presents, immediate IDLE/COPY, zero scene rebuilds; newest buffer survives reveal')
            finally:
                process.terminate(); process.wait(10); channel.close()
                log.seek(0); output = log.read()
                assert process.returncode == 0 and 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output

if __name__ == '__main__':
    run(sys.argv[1], False); run(sys.argv[1], True)
