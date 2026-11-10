#!/usr/bin/env python3
"""Match Java producer acknowledgements independently of Draw and GPU completion."""
import os
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
from test_wire import Client


def run(binary):
    with tempfile.TemporaryDirectory(prefix="ludashi-retirement-") as directory:
        channel, child = socket.socketpair()
        logpath = pathlib.Path(directory) / "server.log"
        with open(logpath, "w+") as log:
            env = dict(os.environ, LUDASHI_HOST_FENCE_DELAY_MS="250",
                       LUDASHI_HOST_BLOCK_DRAW_MS="400", WINLATOR_X11="1",
                       LUDASHI_HOST_TRACE_SCENE="1", LUDASHI_HOST_FAIL_DRAW_SERIAL="12")
            process = subprocess.Popen([binary, str(child.fileno())],
                pass_fds=[child.fileno()], stdout=subprocess.PIPE, stderr=log, text=True, env=env)
            child.close()
            flood_stop = threading.Event()
            flood_thread = None
            try:
                assert process.stdout.readline().strip() == "ready"
                c = Client(channel)
                # All these replies must arrive without another write or an
                # EPOLLIN edge, even when requests sit in BufferedInputStream.
                sequences = [c.send(43) for _ in range(257)]
                for sequence in sequences:
                    packet = c.packet()
                    assert packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == sequence
                win, p0, p1 = c.base | 1, c.base | 2, c.base | 3
                c.send(1, payload=struct.pack("<IIhhHHHHII", win, c.root, 0, 0, 64, 64, 0, 1, 0, 0))
                c.send(8, payload=struct.pack("<I", win))
                for pixmap in (p0, p1):
                    c.send(53, 24, struct.pack("<IIHH", pixmap, win, 64, 64))
                present = c.extension("Present")
                c.send(present, 3, struct.pack("<III", c.base | 4, win, 6))
                c.barrier()

                def submit(pixmap, serial, idle_fence=0):
                    c.send(present, 1, struct.pack("<IIIIIhhIIIIIQQQ", win, pixmap,
                           serial, 0, 0, 0, 0, 0, 0, idle_fence, 0, 0, 0, 0, 0))

                def notification():
                    packet = c.packet()
                    assert packet[0] == 35, packet.hex()
                    return struct.unpack_from("<H", packet, 8)[0], struct.unpack_from("<I", packet, 20)[0]

                def acknowledge(serial):
                    assert notification() == (2, serial)
                    assert notification() == (1, serial)

                first = time.monotonic()
                submit(p0, 10)
                acknowledge(10)
                first_ack = time.monotonic() - first
                assert first_ack < .15, f"IDLE waited for 250 ms GPU completion: {first_ack:.4f}s"
                # Ensure the first frame was actually selected before queuing
                # the next one; producer acknowledgements no longer imply Draw.
                deadline = time.monotonic() + 2
                while "HostScene serial=10 " not in logpath.read_text() and time.monotonic() < deadline:
                    time.sleep(.005)
                assert "HostScene serial=10 " in logpath.read_text()
                submit(p1, 11)
                acknowledge(11)
                assert process.stdout.readline().strip() == "blocked-draw"
                blocked = time.monotonic()

                flood = Client(channel)
                def flood_requests():
                    try:
                        while not flood_stop.is_set():
                            flood.sock.sendall(struct.pack("<BBH", 127, 0, 1) * 4096)
                    except OSError:
                        pass
                flood_thread = threading.Thread(target=flood_requests)
                flood_thread.start()
                # The same producer can queue another frame and get IDLE/COPY
                # while Draw remains blocked. It does not wait for either fence.
                sync = c.extension("SYNC")
                idle_fence = c.base | 5
                c.send(sync, 14, struct.pack("<II", win, idle_fence) + bytes(4)); c.barrier()
                waiter = Client(channel)
                waiter.send(sync, 19, struct.pack("<I", idle_fence))
                waiting_barrier = waiter.send(43)
                submit(p0, 12, idle_fence)
                acknowledge(12)
                waiter.reply(waiting_barrier)
                waiter.close()
                during_block = time.monotonic() - blocked
                assert during_block < .15, f"Producer waited for blocked Draw: {during_block:.4f}s"
                # Free a source pixmap while it is still retained by Draw/GPU.
                c.send(54, payload=struct.pack("<I", p1))
                barrier = c.send(43)
                packet = c.packet()
                assert packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == barrier, packet.hex()
                # Submission 12 fails in the backend after its early ACK. The
                # server must neither repeat notifications nor stall recovery.
                assert process.stdout.readline().strip() == "failed-draw"
                assert time.monotonic() - blocked >= .30
                barrier = c.send(43)
                packet = c.packet()
                assert packet[0] == 1 and struct.unpack_from("<H", packet, 2)[0] == barrier, packet.hex()
                submit(p0, 13)
                acknowledge(13)
                deadline = time.monotonic() + 2
                while "HostScene serial=13 " not in logpath.read_text() and time.monotonic() < deadline:
                    time.sleep(.005)
                assert "HostScene serial=13 " in logpath.read_text()
                flood_stop.set()
                flood_thread.join(5)
                assert not flood_thread.is_alive()
                flood.close()
                c.send(4, payload=struct.pack("<I", win))
                c.barrier()
                c.close()
                print(f"PASS: Java-style IDLE/COPY at {first_ack:.4f}s before GPU completion; blocked Draw, failure recovery, resource retention and request flood")
            finally:
                flood_stop.set()
                process.terminate()
                try:
                    process.wait(10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(); raise
                if flood_thread:
                    flood_thread.join(5)
                channel.close()
                log.seek(0)
                output = log.read()
                assert process.returncode == 0, output
                assert "ERROR: AddressSanitizer" not in output and "runtime error:" not in output, output


if __name__ == "__main__":
    run(sys.argv[1])
