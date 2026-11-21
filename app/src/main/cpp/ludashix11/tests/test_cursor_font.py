#!/usr/bin/env python3
"""Compare every built-in cursor against the vendored X.Org BDF in pointer coordinates."""
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import tempfile

from test_wire import Client


root = Path(__file__).resolve().parents[1]


def reference():
    text = (root / "third_party/cursor-font/cursor.bdf").read_text()
    glyphs = {}
    for block in text.split("STARTCHAR ")[1:]:
        code = int(re.search(r"ENCODING -1 (\d+)", block)[1])
        width, height, left, bottom = map(int, re.search(r"BBX ([-\d ]+)", block)[1].split())
        top = -height - bottom
        rows = block.split("BITMAP\n", 1)[1].split("ENDCHAR", 1)[0].splitlines()
        pixels = set()
        for y, row in enumerate(rows):
            data = bytes.fromhex(row)
            for x in range(width):
                if data[x // 8] & (128 >> (x % 8)):
                    pixels.add((left + x, top + y))
        glyphs[code] = (left, top, width, height, pixels)
    return glyphs


def dump(binary):
    process = subprocess.run([binary, "--cursor-font-dump"], check=True, capture_output=True, text=True)
    assert "runtime error:" not in process.stderr and "ERROR: AddressSanitizer" not in process.stderr, process.stderr
    cursors = {}
    for line in process.stdout.splitlines():
        code, width, height, hot_x, hot_y, *pixels = map(int, line.split())
        assert len(pixels) == width * height
        world = {(x - hot_x, y - hot_y): pixels[y * width + x]
                 for y in range(height) for x in range(width) if pixels[y * width + x]}
        cursors[code] = ((width, height, hot_x, hot_y), world)
    assert set(cursors) == set(range(0, 154, 2))
    return cursors


def wire(binary):
    channel, child = socket.socketpair()
    with channel, child, tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen([binary, str(child.fileno())], pass_fds=(child.fileno(),),
                                   stdout=subprocess.PIPE, stderr=log, text=True)
        child.close()
        try:
            assert process.stdout.readline().strip() == "ready"
            client = Client(channel)
            with client.sock:
                cursor = client.base | 1
                for source, mask in [(154, 69), (68, 65535)]:
                    seq = client.send(94, payload=struct.pack("<III8H", cursor, 42, 42,
                        source, mask, 0, 0, 0, 65535, 65535, 65535))
                    error = client.packet()
                    assert error[0] == 0 and error[1] == 2, error.hex()
                    assert struct.unpack_from("<H", error, 2)[0] == seq
                    client.barrier()
                # An absent mask font ignores its mask glyph, as specified by X11.
                client.send(94, payload=struct.pack("<III8H", cursor, 42, 0,
                    68, 65535, 0, 0, 0, 65535, 65535, 65535))
                client.barrier()
                client.send(95, payload=struct.pack("<I", cursor))
                client.barrier()
        finally:
            process.terminate()
            process.wait(10)
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output


def run(binary, previous=None):
    subprocess.run([sys.executable, str(root / "core/cursor/generate_font.py"), "--check"], check=True)
    glyphs = reference()
    cursors = dump(binary)
    for code, (geometry, actual) in cursors.items():
        left, top, width, height, source = glyphs[code]
        mask_left, mask_top, mask_width, mask_height, mask = glyphs[code + 1]
        expected_left = min(left, mask_left, 0)
        expected_top = min(top, mask_top, 0)
        expected_right = max(left + width, mask_left + mask_width, 1)
        expected_bottom = max(top + height, mask_top + mask_height, 1)
        expected_geometry = (expected_right - expected_left, expected_bottom - expected_top,
                             -expected_left, -expected_top)
        assert geometry == expected_geometry, (code, geometry, expected_geometry)
        expected = {point: 0xff123456 if point in source else 0xffabcdef for point in mask}
        assert actual == expected, code
    if previous:
        before = dump(previous)
        for code, (_, pixels) in cursors.items():
            assert pixels == before[code][1], ("legacy visual mismatch", code)
        print("PASS: all 77 cursor shapes preserve the previous backend's pointer-coordinate pixels")
    subprocess.run([binary, "--cursor-state"], check=True)
    wire(binary)
    print("PASS: upstream font data, all 154 glyphs, compact hotspots and invalid glyph wire recovery")


if __name__ == "__main__":
    run(*sys.argv[1:])
