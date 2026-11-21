#!/usr/bin/env python3
"""CopyGC, immutable rectangle clips, rectangle rasterization and wide-coordinate regressions."""
import random
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
        clients = []
        try:
            assert process.stdout.readline().strip() == "ready"
            c, peer = Client(channel), Client(channel)
            clients += [c, peer]
            pix, source, target, clear, argb, gc32 = [c.base | i for i in range(1, 7)]
            def pixmap(wid, width, height, depth=24):
                c.send(53, depth, struct.pack("<IIHH", wid, c.root, width, height))
            def change(gc, mask, *values):
                c.send(56, payload=struct.pack("<II", gc, mask) + struct.pack("<" + "I" * len(values), *(v & 0xffffffff for v in values)))
            def fill(gc, drawable=pix, width=40, height=32):
                c.send(70, payload=struct.pack("<IIhhHH", drawable, gc, 0, 0, width, height))
            def rectangle(gc, rect, drawable=pix):
                c.send(67, payload=struct.pack("<IIhhHH", drawable, gc, *rect))
            def pixels(drawable=pix, x=0, width=40, height=32):
                reply = c.reply(c.send(73, 2, struct.pack("<IhhHHI", drawable, x, 0, width, height, 0xffffffff)))
                return [v & 0xffffff for v in struct.unpack_from("<" + "I" * (width * height), reply, 32)]
            def clip(gc, x, y, rectangles):
                c.send(59, payload=struct.pack("<Ihh", gc, x, y) + b"".join(struct.pack("<hhHH", *r) for r in rectangles))
            def copy(mask, src=source, dst=target):
                c.send(57, payload=struct.pack("<III", src, dst, mask))
            def error(opcode, payload, code, data=0):
                c.send(opcode, data, payload)
                packet = c.packet()
                assert packet[:2] == bytes((0, code)), packet.hex()
            pixmap(pix, 40, 32)
            pixmap(argb, 4, 4, 32)
            for gc, image in ((source, pix), (target, pix), (clear, pix), (gc32, argb)):
                c.send(55, payload=struct.pack("<III", gc, image, 0))
            c.barrier()
            red = 0x123456
            change(source, 4 | 16, red, 3)
            copy(4 | 16)
            change(source, 4 | 16, 0xabcdef, 9)
            fill(clear)
            rectangle(target, (4, 5, 10, 12))
            actual = pixels()
            assert actual[5 * 40 + 4] == red and actual[10 * 40 + 8] == 0
            assert actual[4 * 40 + 3] == red  # Copied width=3, independently of later source edits.

            # Copying a clip mask does not copy its origin unless requested.
            clip(source, 2, 3, [(0, 0, 4, 3)])
            change(target, (1 << 17) | (1 << 18), 8, 9)
            copy(1 << 19)
            fill(clear); fill(target)
            assert pixels() == [red if 8 <= x < 12 and 9 <= y < 12 else 0 for y in range(32) for x in range(40)]
            copy((1 << 17) | (1 << 18))
            clip(source, 0, 0, [])  # Editing the source must not modify the target's shared region.
            fill(clear); fill(target)
            assert pixels() == [red if 2 <= x < 6 and 3 <= y < 6 else 0 for y in range(32) for x in range(40)]
            copy(1 << 19)
            fill(clear); fill(target)
            assert not any(pixels())  # Empty clip differs from no clip.
            change(target, 1 << 19, 0)
            fill(target)
            assert pixels() == [red] * (40 * 32)

            # Cross-client CopyGC is valid; the copied values outlive the source GC.
            foreign = peer.base | 1
            peer.send(55, payload=struct.pack("<IIII", foreign, pix, 4, 0x778899))
            peer.barrier()
            copy(4, src=foreign)
            c.barrier()
            peer.send(60, payload=struct.pack("<I", foreign)); peer.barrier()
            fill(target)
            assert pixels() == [0x778899] * 1280
            change(target, 4, red)

            rng = random.Random(721)
            for _ in range(120):
                x, y = rng.randrange(-12, 45), rng.randrange(-12, 36)
                width, height = rng.randrange(25), rng.randrange(25)
                stroke = rng.choice((0, 1, 2, 3, 4, 7, 64, 65535))
                change(target, 16, stroke)
                fill(clear)
                rectangle(target, (x, y, width, height))
                n = max(1, stroke)
                half = n // 2
                outer = (x - half, y - half, x + width + n - half, y + height + n - half)
                inner = (outer[0] + n, outer[1] + n, outer[2] - n, outer[3] - n)
                expected = [red if outer[0] <= px < outer[2] and outer[1] <= py < outer[3] and
                            not (inner[0] <= px < inner[2] and inner[1] <= py < inner[3]) else 0
                            for py in range(32) for px in range(40)]
                assert pixels() == expected, (x, y, width, height, stroke)

            change(target, 16, 0)
            clip(target, -2, 4, [(4, 0, 8, 7)])
            fill(clear)
            rectangle(target, (0, 0, 10, 10))
            expected = [red if 2 <= x < 10 and 4 <= y < 11 and (x in (0, 10) or y in (0, 10)) else 0
                        for y in range(32) for x in range(40)]
            assert pixels() == expected
            error(57, struct.pack("<III", source, target, 1 << 23), 2)
            error(57, struct.pack("<III", source, gc32, 4), 8)
            error(57, struct.pack("<III", c.base | 99, target, 4), 13)
            error(57, struct.pack("<III", source, c.base | 99, 4), 13)
            error(57, struct.pack("<II", source, target), 16)
            error(59, struct.pack("<Ihh", target, 0, 0), 2, 4)
            error(67, struct.pack("<II", pix, target) + bytes(4), 16)
            error(67, struct.pack("<IIhhHH", argb, target, 0, 0, 1, 1), 8)
            error(55, struct.pack("<III", c.base | 99, c.base | 100, 0), 9)
            c.send(55, payload=struct.pack("<III", c.base | 99, pix, 0))
            c.barrier()  # A rejected GC creation did not reserve its ID.

            # Large wire extents are clipped to the drawable before rasterization.
            change(target, 1 << 19, 0)
            fill(clear)
            rectangle(target, (-32760, 1, 32780, 3))
            data = pixels()
            assert data[2 * 40 + 20] == red and data[2 * 40 + 19] == 0
            assert data[1 * 40 + 19] == red
        finally:
            for client in clients: client.close()
            process.terminate(); process.wait(10)
            log.seek(0); output = log.read()
            assert process.returncode == 0, output
            assert "runtime error:" not in output and "ERROR: AddressSanitizer" not in output, output
    print("PASS: CopyGC masks/depth/ownership, immutable clips/origins/empty regions, 120 rectangle pixel oracles, validation and large clipped extents")


if __name__ == "__main__": run(sys.argv[1])
