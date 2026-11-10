#!/usr/bin/env python3
"""Pixmap-only scene updates must preserve geometry, culling and visibility."""
import os
import pathlib
import re
import socket
import struct
import subprocess
import sys
import tempfile
import time
from test_wire import Client


def run(binary, filtered, depth=0):
    with tempfile.TemporaryDirectory(prefix="ludashi-present-scene-") as directory:
        channel, child = socket.socketpair()
        logpath = pathlib.Path(directory) / "server.log"
        with open(logpath, "w+") as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True,
                env=dict(os.environ, LUDASHI_HOST_TRACE_SCENE="1", WINLATOR_X11="1",
                         LUDASHI_HOST_HIDDEN_WM_CLASS="explorer.exe" if filtered else ""))
            child.close()
            try:
                assert process.stdout.readline().strip() == "ready"
                # Exercise partial writes and queued reply ordering across two
                # EPOLLOUT enable/drain/disable cycles while reads are paused.
                slow = Client(channel)
                pixmap = slow.base | 1
                slow.send(53, 24, struct.pack("<IIHH", pixmap, slow.root, 512, 512))
                slow.barrier()
                for _ in range(2):
                    sequence = slow.send(73, 2, struct.pack("<IhhHHI", pixmap, 0, 0, 512, 512, 0xffffffff))
                    second = slow.send(73, 2, struct.pack("<IhhHHI", pixmap, 0, 0, 512, 512, 0xffffffff))
                    barrier = slow.send(43)
                    time.sleep(.02)
                    assert len(slow.reply(sequence)) == 32 + 512 * 512 * 4
                    assert len(slow.reply(second)) == 32 + 512 * 512 * 4
                    slow.reply(barrier)
                slow.close()
                c = Client(channel)
                background, win = c.base | 1, c.base | 2
                for window, x, y in ((background, 0, 0), (win, 10, 20)):
                    c.send(1, depth if window == win else 0, payload=struct.pack("<IIhhHHHHII", window, c.root, x, y, 64, 64, 0, 1, 0, 0))
                    c.send(8, payload=struct.pack("<I", window))
                p0, p1, large, small = (c.base | i for i in (3, 4, 5, 6))
                for pixmap, width, height in ((p0, 64, 64), (p1, 64, 64), (large, 1280, 720), (small, 32, 32)):
                    c.send(53, depth or 24, struct.pack("<IIHH", pixmap, win, width, height))
                present = c.extension("Present")
                c.send(present, 3, struct.pack("<III", c.base | 7, win, 6))
                c.barrier()

                def submit(pixmap, serial, x=0, y=0, expected=None):
                    c.send(present, 1, struct.pack("<IIIIIhhIIIIIQQQ", win, pixmap, serial,
                           0, 0, x, y, 0, 0, 0, 0, 0, 0, 0, 0))
                    kinds = []
                    for _ in range(2):
                        packet = c.packet()
                        assert packet[0] == 35 and struct.unpack_from("<I", packet, 20)[0] == serial, packet.hex()
                        kinds.append(struct.unpack_from("<H", packet, 8)[0])
                    assert kinds == [2, 1], kinds
                    if expected:
                        deadline = time.monotonic() + 2
                        while f"HostScene serial={serial} " not in logpath.read_text() and time.monotonic() < deadline:
                            time.sleep(.005)
                        match = re.search(rf"HostScene serial={serial} entries=(\d+) pixmap=(\d+) x=(-?\d+) y=(-?\d+) w=(\d+) h=(\d+)", logpath.read_text())
                        assert match, logpath.read_text()
                        assert tuple(map(int, match.groups())) == expected, match.group()

                def position(x, y):
                    c.send(12, payload=struct.pack("<IHHII", win, 3, 0, x, y))
                    c.barrier()

                submit(p0, 100, expected=(2, p0, 10, 20, 64, 64))
                submit(p1, 101, expected=(2, p1, 10, 20, 64, 64))
                position(100, 200)
                submit(p0, 102, expected=(2, p0, 100, 200, 64, 64))
                submit(p1, 103, 5, 6, expected=(2, p1, 105, 206, 64, 64))
                position(0, 0)
                submit(large, 104, expected=(1, large, 0, 0, 1280, 720))
                submit(small, 105, expected=(2, small, 0, 0, 32, 32))
                c.send(10, payload=struct.pack("<I", win)); c.barrier()
                submit(p0, 106)
                assert "HostScene serial=106 " not in logpath.read_text()
                c.send(8, payload=struct.pack("<I", win)); c.barrier()
                submit(p1, 107, expected=(2, p1, 0, 0, 64, 64))
                c.send(4, payload=struct.pack("<I", win)); c.barrier()
                c.send(1, payload=struct.pack("<IIhhHHHHII", win, c.root, 30, 40, 64, 64, 0, 1, 0, 0))
                c.send(8, payload=struct.pack("<I", win))
                c.send(present, 3, struct.pack("<III", c.base | 8, win, 6)); c.barrier()
                submit(p0, 108, expected=(2, p0, 30, 40, 64, 64))
                time.sleep(1.1)
                submit(p1, 109, expected=(2, p1, 30, 40, 64, 64))
                deadline = time.monotonic() + 2
                while "scene_patch=" not in logpath.read_text() and time.monotonic() < deadline: time.sleep(.01)
                counters = re.search(r"scene_patch=(\d+) scene_build=(\d+)", logpath.read_text())
                assert counters and all(int(n) > 0 for n in counters.groups()), logpath.read_text()
                # Match shortcut filtering while leaving the container desktop
                # visible when no class filter is configured.
                def wm_class(value):
                    c.send(18, payload=struct.pack("<IIIB3xI", background, 67, 31, 8, len(value))
                           + value + bytes((-len(value)) % 4))
                    c.barrier()
                wm_class(b"desktop\0wine.explorer.exe\0")
                visible = 1 if filtered else 2
                submit(p0, 110, expected=(visible, p0, 30, 40, 64, 64))
                child_window = c.base | 10
                c.send(1, payload=struct.pack("<IIhhHHHHII", child_window, background, 2, 3, 32, 32, 0, 1, 0, 0))
                c.send(8, payload=struct.pack("<I", child_window)); c.barrier()
                submit(p1, 111, expected=(visible + 1, p1, 30, 40, 64, 64))
                # A class change and deletion must invalidate the cached scene.
                wm_class(b"desktop\0other.exe\0")
                submit(p0, 112, expected=(3, p0, 30, 40, 64, 64))
                wm_class(b"desktop\0explorer.exe\0")
                submit(p1, 113, expected=(visible + 1, p1, 30, 40, 64, 64))
                c.send(19, payload=struct.pack("<II", background, 67)); c.barrier()
                submit(p0, 114, expected=(3, p0, 30, 40, 64, 64))
                probe = c.base | 11
                c.send(1, payload=struct.pack("<IIhhHHHHII", probe, c.root, 0, 0, 1, 1, 0, 1, 0, 0))
                c.send(8, payload=struct.pack("<I", probe)); c.barrier()
                submit(p1, 115, expected=(3, p1, 30, 40, 64, 64))
                # Motion changes only the cursor snapshot, even with a game
                # pixmap and mapped/filtered desktop children in the scene.
                cursor_bitmap = c.base | 15
                c.send(53, 1, struct.pack("<IIHH", cursor_bitmap, c.root, 16, 16))
                cursor = c.base | 12
                c.send(93, payload=struct.pack("<III8H", cursor, cursor_bitmap, 0, 0, 0, 0,
                                              65535, 65535, 65535, 2, 3))
                cursor_window = c.base | 14
                c.send(1, payload=struct.pack("<IIhhHHHHII", cursor_window, c.root,
                                             0, 0, 1280, 720, 0, 2, 0, 1 << 14)
                       + struct.pack("<I", cursor))
                c.send(8, payload=struct.pack("<I", cursor_window))
                def warp(x, y):
                    c.send(41, payload=struct.pack("<IIhhHHhh", 0, c.root, 0, 0, 0, 0, x, y))
                warp(300, 400); c.barrier()
                time.sleep(1.1)
                reports = len(re.findall(r"scene_patch=", logpath.read_text()))
                submit(p0, 116, expected=(3, p0, 30, 40, 64, 64))
                def wait_report(previous):
                    deadline = time.monotonic() + 2
                    while len(re.findall(r"scene_patch=", logpath.read_text())) <= previous and time.monotonic() < deadline:
                        time.sleep(.005)
                    values = re.findall(r"scene_patch=(\d+) scene_build=(\d+) cursor_patch=(\d+)", logpath.read_text())
                    assert len(values) > previous, logpath.read_text()
                    return tuple(map(int, values[-1]))
                wait_report(reports)
                first_cursor = re.search(r"HostScene serial=116 .*cursor=(\d+) cursor_x=298 cursor_y=397", logpath.read_text())
                assert first_cursor, logpath.read_text()
                reports = len(re.findall(r"scene_patch=", logpath.read_text()))
                for i in range(128): warp(400 + i, 410)
                for _ in range(64): warp(527, 410) # Duplicate input must not rebuild the scene either.
                c.barrier(); time.sleep(1.1)
                submit(p1, 117, expected=(3, p1, 30, 40, 64, 64))
                patches, builds, cursors = wait_report(reports)
                assert patches > 0 and builds == 0 and cursors == 128, (patches, builds, cursors)
                assert re.search(rf"HostScene serial=117 .*cursor={first_cursor[1]} cursor_x=525 cursor_y=407", logpath.read_text()), logpath.read_text()
                # A new cursor image/hotspot must replace the cached clone.
                cursor2 = c.base | 13
                c.send(93, payload=struct.pack("<III8H", cursor2, cursor_bitmap, 0, 0, 0, 0,
                                              65535, 65535, 65535, 4, 5))
                c.send(2, payload=struct.pack("<III", cursor_window, 1 << 14, cursor2)); c.barrier()
                submit(p0, 118, expected=(3, p0, 30, 40, 64, 64))
                replacement = re.search(r"HostScene serial=118 .*cursor=(\d+) cursor_x=523 cursor_y=405", logpath.read_text())
                assert replacement and replacement[1] != first_cursor[1], logpath.read_text()
                c.send(4, payload=struct.pack("<I", cursor_window)); c.barrier(); c.close()
                print(f"PASS: socket recovery, pixmap/geometry/culling, window ID reuse; WM_CLASS filter={filtered}, visible children, cursor snapshots without scene rebuilds")
            finally:
                process.terminate()
                try: process.wait(10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(); raise
                channel.close(); log.seek(0); output = log.read()
                assert process.returncode == 0, output
                assert "ERROR: AddressSanitizer" not in output and "runtime error:" not in output, output


if __name__ == "__main__":
    run(sys.argv[1], False)
    run(sys.argv[1], True)
    run(sys.argv[1], False, 32)
    run(sys.argv[1], True, 32)
