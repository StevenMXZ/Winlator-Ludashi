#!/usr/bin/env python3
"""Synchronous Wine property reads, Mesa direct GLX negotiation, bitmap cursors and HUD."""
import os, socket, struct, subprocess, sys, tempfile
from test_wire import Client

def run(binary):
    unit = subprocess.run([binary, '--cursor-hud'], capture_output=True, text=True)
    assert unit.returncode == 0, unit.stdout + unit.stderr
    print('PASS:', unit.stdout.strip())
    channel, child = socket.socketpair()
    with tempfile.TemporaryFile(mode='w+') as log:
        process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                                   stdout=subprocess.PIPE, stderr=log, text=True)
        child.close()
        try:
            assert process.stdout.readline().strip() == 'ready'
            c = Client(channel)
            def intern(name):
                b = name.encode()
                p = c.reply(c.send(16, payload=struct.pack('<HH',len(b),0)+b+b'\0'*((-len(b))%4)))
                return struct.unpack_from('<I',p,8)[0]
            prop = intern('COMPAT_REGRESSION_PROPERTY')
            for fmt, data in [(8,b'a'),(8,b'hello'),(16,struct.pack('<HHH',100,200,300)),(32,struct.pack('<II',0x12345678,0xabcdef01))]:
                c.send(18,0,struct.pack('<IIIB3xI',c.root,prop,31,fmt,len(data)//(fmt//8))+data+b'\0'*((-len(data))%4))
                # No barrier or following request: the complete reply must arrive on its own.
                p=c.reply(c.send(20,0,struct.pack('<IIIII',c.root,prop,31,0,128)))
                assert p[1]==fmt and p[32:32+len(data)]==data,p.hex()
                assert struct.unpack_from('<II',p,12)==(0,len(data)//(fmt//8)),p.hex()
                c.barrier()
            p=c.reply(c.send(20,0,struct.pack('<IIIII',c.root,prop,6,0,128)))
            assert struct.unpack_from('<II',p,12)==(8,0),p.hex()
            p=c.reply(c.send(20,1,struct.pack('<IIIII',c.root,prop,31,0,128)))
            assert p[32:40]==data,p.hex()
            p=c.reply(c.send(20,0,struct.pack('<IIIII',c.root,prop,31,0,128)))
            assert p[1]==0,p.hex()
            print('PASS: property payload arrives without another request; 8/16/32-bit values, mismatch and delete semantics')
            glx=c.extension('GLX')
            p=c.reply(c.send(glx,7,struct.pack('<II',1,4)))
            assert struct.unpack_from('<II',p,8)==(1,4),p.hex()
            for minor,payload in [(18,struct.pack('<I',0)),(19,struct.pack('<II',0,1)),(19,struct.pack('<II',0,2))]:
                p=c.reply(c.send(glx,minor,payload));length=struct.unpack_from('<I',p,12)[0]
                assert p[32+length-1]==0 and length>1,p.hex()
            p=c.reply(c.send(glx,21,struct.pack('<I',0)))
            n,attrs=struct.unpack_from('<II',p,8); assert n==6 and len(p)==32+n*attrs*8,p.hex()
            for i in range(n):
                pairs=struct.unpack_from('<'+'I'*(attrs*2),p,32+i*attrs*8)
                cfg=dict(zip(pairs[::2],pairs[1::2]))
                assert cfg[0x8013]==i+1 and cfg[0x8010]==3 and cfg[0x8011]==1
            p=c.reply(c.send(glx,14,struct.pack('<I',0)))
            assert len(p)==32+6*18*4,p.hex()
            visual=struct.unpack_from('<I',p,32)[0]
            win,ctx,gwin,pix,gpix,gc,cur=(c.base|i for i in range(1,8))
            c.send(1,payload=struct.pack('<IIhhHHHHII',win,c.root,0,0,64,64,0,1,0,0))
            c.send(8,payload=struct.pack('<I',win))
            c.send(glx,3,struct.pack('<IIIIB3x',ctx,visual,0,0,1))
            p=c.reply(c.send(glx,6,struct.pack('<I',ctx)));assert p[8]==1,p.hex()
            p=c.reply(c.send(glx,25,struct.pack('<I',ctx)));assert struct.unpack_from('<I',p,8)[0]==4,p.hex()
            c.send(glx,31,struct.pack('<IIIII',0,6,win,gwin,0))
            p=c.reply(c.send(glx,29,struct.pack('<I',gwin)))
            pairs=struct.unpack_from('<10I',p,32); a=dict(zip(pairs[::2],pairs[1::2]));assert a[0x801d]==64 and a[0x8013]==6
            c.send(12,payload=struct.pack('<IHHII',win,12,0,80,48))
            p=c.reply(c.send(glx,29,struct.pack('<I',gwin)))
            pairs=struct.unpack_from('<10I',p,32);a=dict(zip(pairs[::2],pairs[1::2]));assert (a[0x801d],a[0x801e])==(80,48)
            c.send(53,24,struct.pack('<IIHH',pix,win,16,8))
            c.send(glx,22,struct.pack('<IIIII',0,2,pix,gpix,0));c.barrier()
            p=c.reply(c.send(glx,29,struct.pack('<I',gpix)))
            pairs=struct.unpack_from('<10I',p,32);a=dict(zip(pairs[::2],pairs[1::2]));assert a[0x8010]==2 and a[0x801d]==16
            c.send(glx,4,struct.pack('<I',ctx))
            c.send(glx,24,struct.pack('<IIIIIB3x',ctx,1,0,0x8014,0,1));c.barrier()
            c.send(glx,4,struct.pack('<I',ctx))
            c.send(glx,34,struct.pack('<IIIIB3xI6I',ctx,6,0,0,1,3,0x2091,3,0x2092,3,0x9126,2));c.barrier()
            # Reject indirect GL and malformed ARB attributes without disconnecting.
            bad=c.base|20
            c.send(glx,24,struct.pack('<IIIIIB3x',bad,1,0,0x8014,0,0));p=c.packet();assert p[0]==0 and p[10]==glx and struct.unpack_from('<H',p,8)[0]==24,p.hex()
            c.send(glx,34,struct.pack('<IIIIB3xI',bad,1,0,0,1,100));p=c.packet();assert p[0]==0 and p[1]==16,p.hex()
            c.send(glx,23,struct.pack('<I',gpix));c.send(glx,32,struct.pack('<I',gwin));c.send(glx,4,struct.pack('<I',ctx));c.barrier()
            # XYPixmap/ZPixmap depth-one data must ignore the GC foreground/background.
            mono=c.base|21
            c.send(53,1,struct.pack('<IIHH',mono,win,3,1));c.send(55,payload=struct.pack('<III',gc,mono,0))
            c.send(72,2,struct.pack('<IIHHhhBB2x',mono,gc,3,1,0,0,0,1)+b'\x05\0\0\0')
            p=c.reply(c.send(73,2,struct.pack('<IhhHHI',mono,0,0,3,1,0xffffffff)))
            assert struct.unpack_from('<III',p,32)==(1,0,1),p.hex()
            c.send(93,payload=struct.pack('<III6H2H',cur,mono,mono,0x1234,0x5678,0x9abc,0xabcd,0xcdef,0x1357,1,0));c.barrier()
            c.send(96,payload=struct.pack('<I6H',cur,0x8765,0x4321,0xabcd,0xeeee,0xdddd,0xcccc));c.barrier()
            c.close()
            print('PASS: direct GLX metadata, contexts, window/pixmap lifecycle, resize, errors; bitmap cursor upload/recolor')
        finally:
            process.terminate();process.wait(10);channel.close();log.seek(0);output=log.read()
            assert process.returncode==0,output
            assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output,output
if __name__=='__main__':run(sys.argv[1])
