#!/usr/bin/env python3
"""An acquire stall must not make native X11 sample an obsolete scene."""
import os, pathlib, re, socket, struct, subprocess, sys, tempfile, time
from test_wire import Client

def run(binary, unmap):
    with tempfile.TemporaryDirectory(prefix='ludashi-latch-') as directory:
        channel, child = socket.socketpair(); logpath = pathlib.Path(directory) / 'server.log'
        with logpath.open('w+') as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True,
                env=dict(os.environ, LUDASHI_HOST_TRACE_SCENE='1', WINLATOR_X11='1',
                         LUDASHI_HOST_BLOCK_ACQUIRE_MS='600', LUDASHI_HOST_FENCE_DELAY_MS='50'))
            child.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                c = Client(channel); present = c.extension('Present')
                window, p0, p1, p2, eid = [c.base | i for i in (1, 2, 3, 4, 5)]
                c.send(1, payload=struct.pack('<IIhhHHHHII', window, c.root, 0, 0, 64, 64, 0, 1, 0, 0))
                c.send(8, payload=struct.pack('<I', window))
                for pixmap in (p0, p1, p2): c.send(53, 24, struct.pack('<IIHH', pixmap, window, 64, 64))
                bitmap, cursor = c.base | 6, c.base | 7
                c.send(53, 1, struct.pack('<IIHH', bitmap, c.root, 16, 16))
                c.send(93, payload=struct.pack('<III8H', cursor, bitmap, 0, 0, 0, 0, 65535, 65535, 65535, 0, 0))
                c.send(2, payload=struct.pack('<III', c.root, 1 << 14, cursor))
                c.send(present, 3, struct.pack('<III', eid, window, 6)); c.barrier()
                def submit(pixmap, serial):
                    c.send(present, 1, struct.pack('<IIIIIhhIIIIIQQQ', window, pixmap, serial,
                           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                    packets = [c.packet(), c.packet()]
                    assert [struct.unpack_from('<H', p, 8)[0] for p in packets] == [2, 1]
                    assert all(p[0] == 35 and struct.unpack_from('<I', p, 20)[0] == serial for p in packets)
                submit(p0, 41)
                assert process.stdout.readline().strip() == 'blocked-acquire'
                start = time.monotonic()
                for serial in range(42, 90): submit(p1 if serial % 2 == 0 else p2, serial)
                c.send(12, payload=struct.pack('<IHHII', window, 3, 0, 100, 200))
                c.send(41, payload=struct.pack('<IIhhHHhh', 0, c.root, 0, 0, 0, 0, 300, 400))
                c.send(54, payload=struct.pack('<I', p0)); c.send(54, payload=struct.pack('<I', p1))
                if unmap: c.send(10, payload=struct.pack('<I', window))
                c.barrier()
                assert time.monotonic() - start < .35, 'X11/IDLE blocked on acquire'
                expected = 0 if unmap else 89
                pattern = f'HostLatch initial_serial=41 selected_serial={expected} entries={0 if unmap else 1}'
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    output = logpath.read_text()
                    if pattern in output and (unmap or 'HostScene serial=89 ' in output): break
                    time.sleep(.005)
                output = logpath.read_text(); assert pattern in output, output
                assert 'HostScene serial=41 ' not in output, output
                if not unmap:
                    assert re.search(rf'HostScene serial=89 entries=1 pixmap={p2} x=100 y=200 .*cursor_x=300 cursor_y=400', output), output
                # A reveal keeps the latest content; no stale callback is needed.
                if unmap: c.send(8, payload=struct.pack('<I', window)); c.barrier()
                time.sleep(1.1); submit(p2, 90)
                deadline = time.monotonic() + 2
                while 'queue_us(avg/max)=' not in logpath.read_text() and time.monotonic() < deadline: time.sleep(.005)
                output = logpath.read_text()
                reports = re.findall(r'queue_us\(avg/max\)=([\d.]+)/([\d.]+)', output)
                assert reports, output
                assert all(float(r[1]) < 2000000 for r in reports), reports # Includes the deliberate 600 ms wait, never an unsigned negative age.
                c.close()
                print(f'PASS: blocked acquire chooses latest scene (unmap={unmap}), immediate 48 producer acknowledgements, geometry/cursor updates, freed obsolete buffers and sane queue age')
            finally:
                process.terminate(); process.wait(10); channel.close()
                log.seek(0); output = log.read()
                assert process.returncode == 0 and 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output

if __name__ == '__main__':
    # Check the production hook's placement as well as the adapter/scene behavior.
    source = (pathlib.Path(__file__).parents[6] / 'app/src/main/cpp/winlator/renderer/vulkan/VulkanRendererContext.cpp').read_text()
    start = source.index('void VulkanRendererContext::renderFrame()')
    acquire = source.index('vk_.AcquireNextImageKHR(', start)
    wait = source.index('imgInFlight[imgIdx]=inFlightFences[currentFrame]', acquire)
    latch = source.index('if (nativeFrameReady) nativeFrameReady(nativeFrameReadyData)', wait)
    snapshot = source.index('pendingFrameSerial=++submittedSerial', latch)
    assert acquire < wait < latch < snapshot
    run(sys.argv[1], False); run(sys.argv[1], True)
