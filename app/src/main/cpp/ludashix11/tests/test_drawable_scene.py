#!/usr/bin/env python3
"""Software drawable updates patch the scene; offscreen writes do not redraw."""
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

def run(binary):
    channel,child=socket.socketpair()
    with tempfile.TemporaryDirectory(prefix='ludashi-drawable-scene-') as directory:
        path=pathlib.Path(directory)/'log'
        with path.open('w+') as log:
            process=subprocess.Popen([binary,str(child.fileno())],pass_fds=[child.fileno()],
                stdout=subprocess.PIPE,stderr=log,text=True,
                env=dict(os.environ,LUDASHI_HOST_TRACE_SCENE='1',WINLATOR_X11='1'))
            child.close()
            try:
                assert process.stdout.readline().strip()=='ready'
                c=Client(channel); win,pixmap,gc=(c.base|i for i in (1,2,3))
                c.send(1,payload=struct.pack('<IIhhHHHHII',win,c.root,10,20,64,64,0,1,0,0))
                c.send(8,payload=struct.pack('<I',win))
                c.send(53,24,struct.pack('<IIHH',pixmap,win,64,64))
                c.send(55,payload=struct.pack('<III',gc,win,0));c.barrier()
                def upload(drawable,color):
                    c.send(72,2,struct.pack('<IIHHhhBBH',drawable,gc,64,64,0,0,0,24,0)+struct.pack('<I',color)*4096)
                def sampled(color):
                    text=f'HostCpu x=10 y=20 first={color}'
                    deadline=time.monotonic()+3
                    while text not in path.read_text():
                        assert time.monotonic()<deadline,path.read_text()
                        time.sleep(.005)
                upload(win,0xff112233);c.barrier();sampled(0xff112233)
                time.sleep(1.1)
                upload(win,0xff223344);c.barrier();sampled(0xff223344)
                deadline=time.monotonic()+3
                while not re.findall(r'drawable_patch=\d+ offscreen_write=\d+',path.read_text()):
                    assert time.monotonic()<deadline;time.sleep(.005)
                reports=len(re.findall(r'drawable_patch=',path.read_text()))
                # Pixmaps are not attached to any window or selected by Present.
                for i in range(128):upload(pixmap,0xff000000|i)
                c.barrier()
                # CopyArea must publish the latest offscreen pixels without a tree rebuild.
                c.send(62,payload=struct.pack('<IIIhhhhHH',pixmap,win,gc,0,0,0,0,64,64));c.barrier()
                sampled(0xff00007f)
                time.sleep(1.1)
                upload(win,0xff334455);c.barrier();sampled(0xff334455)
                deadline=time.monotonic()+3
                while len(re.findall(r'drawable_patch=',path.read_text()))<=reports:
                    assert time.monotonic()<deadline,path.read_text();time.sleep(.005)
                metrics=re.findall(r'scene_patch=(\d+) scene_build=(\d+) cursor_patch=(\d+) drawable_patch=(\d+) offscreen_write=(\d+)',path.read_text())
                _,builds,_,patches,offscreen=map(int,metrics[-1])
                assert builds==0 and patches==2 and offscreen==128,metrics
                # Hidden writes must be visible on a later map/tree rebuild.
                c.send(10,payload=struct.pack('<I',win));c.barrier()
                upload(win,0xff445566);c.barrier()
                c.send(8,payload=struct.pack('<I',win));c.barrier();sampled(0xff445566)
                c.close()
                print('PASS: visible uploads/CopyArea patch snapshots; 128 offscreen writes avoid scene rebuilds; hidden content survives remap')
            finally:
                process.terminate();process.wait(10);channel.close()
                assert process.returncode==0,path.read_text()
                assert 'ERROR: AddressSanitizer' not in path.read_text() and 'runtime error:' not in path.read_text(),path.read_text()
if __name__=='__main__':run(sys.argv[1])
