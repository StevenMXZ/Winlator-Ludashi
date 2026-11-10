#!/usr/bin/env python3
"""Mesa DRI3/Freedreno geometry notifications use Present GE, not core events."""
import os, pathlib, socket, struct, subprocess, sys, tempfile
from test_wire import Client

def run(binary):
    with tempfile.TemporaryDirectory(prefix='ludashi-present-configure-') as directory:
        channel, child = socket.socketpair(); logpath = pathlib.Path(directory) / 'server.log'
        with logpath.open('w+') as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True)
            child.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                owner, observer, frames = Client(channel), Client(channel), Client(channel)
                present = owner.extension('Present')
                parent, other_parent, window, eid, pixmap = [owner.base | i for i in (1, 2, 3, 4, 5)]
                observer_eid, frame_eid = observer.base | 1, frames.base | 1
                def create(client, wid, parent_id, x, y, w, h, border=0):
                    client.send(1, payload=struct.pack('<IIhhHHHHII', wid, parent_id, x, y, w, h, border, 1, 0, 0))
                    client.send(8, payload=struct.pack('<I', wid))
                def barrier(client):
                    # Unlike Client.reply(), this must not silently discard unwanted events.
                    seq = client.send(43); p = client.packet()
                    assert p[0] == 1 and struct.unpack_from('<H', p, 2)[0] == seq, p.hex()
                def select(client, event, wid, mask):
                    client.send(present, 3, struct.pack('<III', event, wid, mask)); barrier(client)
                def configure(wid, mask, *values):
                    return owner.send(12, payload=struct.pack('<IHH', wid, mask, 0) + struct.pack('<'+'I'*len(values), *(v & 0xffffffff for v in values)))
                def event(client, event_id, wid, x, y, w, h, flags=0, seq=None):
                    p = client.packet()
                    assert len(p) == 40 and p[0] == 35 and p[1] == present, p.hex()
                    assert struct.unpack_from('<I', p, 4)[0] == 2 and struct.unpack_from('<H', p, 8)[0] == 0, p.hex()
                    assert struct.unpack_from('<H', p, 2)[0] == (client.seq if seq is None else seq), p.hex()
                    assert struct.unpack_from('<IIhhHHhhHHI', p, 12) == (event_id, wid, x, y, w, h, 0, 0, w, h, flags), p.hex()
                def both(seq, x, y, w, h, flags=0):
                    event(owner, eid, window, x, y, w, h, flags, seq)
                    event(observer, observer_eid, window, x, y, w, h, flags)
                    barrier(owner); barrier(observer); barrier(frames)
                create(owner, parent, owner.root, 100, 200, 256, 192, 3)
                create(owner, other_parent, owner.root, 400, 300, 256, 192, 5)
                create(owner, window, parent, 4, 6, 64, 48, 2); barrier(owner)
                # No core StructureNotify selection: Present itself must be sufficient.
                select(owner, eid, window, 1)
                select(observer, observer_eid, window, 1)
                select(frames, frame_eid, window, 6)
                seq = configure(window, 3, -4, -6); both(seq, -4, -6, 64, 48)
                seq = configure(window, 12, 128, 96); both(seq, -4, -6, 128, 96)
                configure(window, 15, -4, -6, 128, 96)
                barrier(owner); barrier(observer); barrier(frames) # Same geometry: no event.
                configure(window, 64, 0)
                barrier(owner); barrier(observer); barrier(frames) # Stacking-only: no reallocation signal.
                seq = configure(window, 15, 0, 0, 640, 480); both(seq, 0, 0, 640, 480)
                seq = configure(window, 15, -4, -6, 128, 96); both(seq, -4, -6, 128, 96)
                # Parent translation does not change this window's parent-relative geometry.
                configure(parent, 3, 101, 202); barrier(owner); barrier(observer); barrier(frames)
                # Parent resize changes the child's relative origin through CenterGravity.
                seq = configure(parent, 12, 384, 320); both(seq, 60, 58, 128, 96)
                # Reparent to an offset parent; requested coordinates are relative to the NEW parent.
                seq = owner.send(7, payload=struct.pack('<IIhh', window, other_parent, 7, 9))
                both(seq, 7, 9, 128, 96)
                p = owner.reply(owner.send(40, payload=struct.pack('<IIhh', window, owner.root, 0, 0)))
                assert struct.unpack_from('<hh', p, 12) == (414, 316), p.hex()
                # StaticGravity holds root position while changing parent-relative coordinates.
                owner.send(2, payload=struct.pack('<III', window, 32, 10)); barrier(owner)
                seq = configure(other_parent, 15, 410, 320, 512, 512)
                both(seq, -3, -11, 128, 96)
                # Full Present event mask filtering, including disable/re-enable.
                select(observer, observer_eid, window, 0)
                seq = configure(window, 3, 8, 10)
                event(owner, eid, window, 8, 10, 128, 96, seq=seq)
                barrier(owner); barrier(observer); barrier(frames)
                select(observer, observer_eid, window, 1)
                seq = configure(window, 16, 4); both(seq, 8, 10, 128, 96)
                # Regular frames still deliver only IDLE/COPY to the frame subscriber.
                owner.send(53, 24, struct.pack('<IIHH', pixmap, window, 128, 96)); barrier(owner)
                for serial in range(20):
                    owner.send(present, 1, struct.pack('<IIIIIhhIIIIIQQQ', window, pixmap, serial,
                               0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                    packets = [frames.packet(), frames.packet()]
                    assert [struct.unpack_from('<H', p, 8)[0] for p in packets] == [2, 1]
                    assert all(p[0] == 35 and struct.unpack_from('<I', p, 20)[0] == serial for p in packets)
                barrier(owner); barrier(observer); barrier(frames)
                seq = owner.send(4, payload=struct.pack('<I', window))
                both(seq, 8, 10, 128, 96, 1)
                # Old subscriptions cannot attach themselves to a reused window ID.
                create(owner, window, parent, 2, 3, 64, 48); barrier(owner)
                configure(window, 3, 5, 6); barrier(owner); barrier(observer); barrier(frames)
                select(observer, observer_eid, window, 1)
                # Creator disconnect must notify another client's surviving subscription once.
                owner.close()
                event(observer, observer_eid, window, 5, 6, 64, 48, 1)
                barrier(observer); barrier(frames)
                observer.close(); frames.close()
                print('PASS: Present ConfigureNotify 40-byte GE, per-client sequence/eid/mask, move/resize/fullscreen, gravity/reparent, destroyed flag, disconnect and ID reuse; no geometry events on regular Presents')
            finally:
                process.terminate(); process.wait(10); channel.close()
                log.seek(0); output = log.read()
                assert process.returncode == 0 and 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output

if __name__ == '__main__': run(sys.argv[1])
