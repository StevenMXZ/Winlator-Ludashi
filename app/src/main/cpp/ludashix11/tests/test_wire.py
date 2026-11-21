#!/usr/bin/env python3
"""Exercise the native protocol over real Unix sockets, without Android or Wine."""
import array, os, pathlib, socket, struct, subprocess, sys, tempfile, time

def exact(s, n):
    data = b''
    while len(data) < n:
        piece = s.recv(n-len(data))
        if not piece: raise AssertionError('unexpected EOF')
        data += piece
    return data

class Client:
    def __init__(self, path, fragmented=False, opening=b""):
        self.sock, peer = socket.socketpair()
        self.sock.settimeout(3)
        path.sendmsg([b'c'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [peer.fileno()]))])
        peer.close()
        auth = b'fake' + b'1234'
        prefix = struct.pack('<BBHHHHH', 108, 0, 11, 0, 4, 4, 0)
        if fragmented:
            for part in [prefix[:3], prefix[3:9], prefix[9:]+auth[:2], auth[2:]+opening]:
                self.sock.sendall(part); time.sleep(.002)
        else: self.sock.sendall(struct.pack('<BBHHHHH',108,0,11,0,0,0,0) + opening)
        p=exact(self.sock,8); assert p[0]==1,p
        setup=exact(self.sock,struct.unpack_from('<H',p,6)[0]*4)
        self.base,self.mask=struct.unpack_from('<II',setup,4)
        vendor=struct.unpack_from('<H',setup,16)[0]
        offset=32+((vendor+3)&~3)+setup[21]*8
        self.root=struct.unpack_from('<I',setup,offset)[0]
        self.seq=0
    def send(self, opcode, data=0, payload=b'', fragment=False):
        packet=struct.pack('<BBH',opcode,data,(len(payload)+4)//4)+payload
        self.seq+=1
        if fragment:
            for i in range(0,len(packet),3): self.sock.sendall(packet[i:i+3])
        else:self.sock.sendall(packet)
        return self.seq
    def packet(self):
        p=exact(self.sock,32)
        if p[0] in (1,35): p+=exact(self.sock,struct.unpack_from('<I',p,4)[0]*4)
        return p
    def reply(self, sequence=None):
        while True:
            p=self.packet()
            assert p[0]!=0, ('X11 error',p.hex())
            if p[0]==1 and (sequence is None or struct.unpack_from('<H',p,2)[0]==sequence):return p
    def barrier(self):return self.reply(self.send(43))
    def extension(self,name):
        b=name.encode();seq=self.send(98,payload=struct.pack('<HH',len(b),0)+b+b'\0'*((-len(b))%4))
        p=self.reply(seq);assert p[8],name;return p[9]
    def close(self):self.sock.close()

def run(binary):
    with tempfile.TemporaryDirectory(prefix='ludashix11-') as directory:
        path, channel = socket.socketpair()
        log=open(pathlib.Path(directory)/'server.log','w+')
        process=subprocess.Popen([binary,str(channel.fileno())],pass_fds=[channel.fileno()],stdout=subprocess.PIPE,stderr=log,text=True)
        channel.close()
        try:
            assert process.stdout.readline().strip()=='ready'
            # Put the last setup fragment and more than a full command budget
            # in one write. Errors and trailing replies must drain without
            # waiting for any subsequent client socket activity.
            invalid_window = 0x1fffffff
            initial = struct.pack('<BBHI', 15, 77, 2, invalid_window)
            initial += struct.pack('<BBH', 127, 0, 1) * 65
            initial += struct.pack('<BBH', 43, 0, 1)
            with Client(path, fragmented=True, opening=initial).sock as startup_socket:
                failure = exact(startup_socket, 32)
                assert failure == struct.pack('<BBHIHB21x', 0, 3, 1, invalid_window, 0, 15), failure.hex()
                reply = exact(startup_socket, 32)
                assert reply[0] == 1 and struct.unpack_from('<H', reply, 2)[0] == 67, reply.hex()

            c=Client(path,True)
            extensions={name:c.extension(name) for name in ['BIG-REQUESTS','DRI3','Present','SYNC','RANDR','MIT-SHM']}
            for name,opcode in extensions.items():
                if name=='BIG-REQUESTS': c.reply(c.send(opcode))
                elif name=='MIT-SHM': c.reply(c.send(opcode))
                elif name=='SYNC': c.reply(c.send(opcode,payload=bytes([3,1,0,0])))
                else: c.reply(c.send(opcode,payload=struct.pack('<II',1,2)))
            # Mesa's GLX/Zink probe requires both DRI3 1.2 and Present 1.2.
            # Advertising Present 1.0 makes Mesa print "DRI3 not available"
            # even when X11 advertises DRI3 1.2.
            for extension in ('DRI3', 'Present'):
                p = c.reply(c.send(extensions[extension], payload=struct.pack('<II', 1, 2)))
                assert struct.unpack_from('<II', p, 8) == (1, 2), (extension, p.hex())
            # Fragmented core requests, drawable pixels, colors and geometry.
            win=c.base|1;pix=c.base|2;gc=c.base|3
            c.send(1,payload=struct.pack('<IIhhHHHHII',win,c.root,0,0,64,64,0,1,0,0),fragment=True)
            c.send(8,payload=struct.pack('<I',win));c.barrier()
            p=c.reply(c.send(14,payload=struct.pack('<I',win)));assert struct.unpack_from('<HH',p,16)==(64,64)
            keymap_event = bytes((0x80 | 11, *range(1, 32)))
            c.send(25, payload=struct.pack('<II', win, 0) + keymap_event)
            assert c.packet() == keymap_event
            c.send(53,24,struct.pack('<IIHH',pix,win,64,64))
            c.send(55,payload=struct.pack('<III',gc,pix,4)+struct.pack('<I',0x123456))
            c.send(70,payload=struct.pack('<IIhhHH',pix,gc,0,0,64,64))
            p=c.reply(c.send(73,2,struct.pack('<IhhHHI',pix,0,0,1,1,0xffffffff)))
            assert struct.unpack_from('<I',p,32)[0]&0xffffff==0x123456,p.hex()
            # BIG-REQUESTS uses a 32-bit length and a shifted wire header.
            bigpix=c.base|20
            c.send(53,24,struct.pack('<IIHH',bigpix,win,512,512))
            image=struct.pack('<I',0xabcdef)*512*512
            body=struct.pack('<IIHHhhBBH',bigpix,gc,512,512,0,0,0,24,0)+image
            c.seq+=1
            packet=struct.pack('<BBHI',72,2,0,(len(body)+8)//4)+body
            for offset in range(0,len(packet),8191):c.sock.sendall(packet[offset:offset+8191])
            p=c.reply(c.send(73,2,struct.pack('<IhhHHI',bigpix,511,511,1,1,0xffffffff)))
            assert struct.unpack_from('<I',p,32)[0]&0xffffff==0xabcdef
            # Keyboard map checks every range; ASAN detects the upstream index bug.
            p=c.reply(c.send(101,payload=struct.pack('<BBH',8,248,0)));assert len(p)==32+248*8
            # Atom IDs are shared across clients of the same display.
            def intern(client, name, existing=False):
                payload = struct.pack('<HH', len(name), 0) + name + b'\0' * (-len(name) % 4)
                return struct.unpack_from('<I', client.reply(client.send(16, existing, payload)), 8)[0]
            assert intern(c, b'WM_CLASS', True) == 67
            test_atom = b'_LUDASHI_WIRE_TEST'
            atom = intern(c, test_atom)
            assert atom > 68 and intern(c, test_atom) == atom
            p = c.reply(c.send(17, payload=struct.pack('<I', atom)))
            assert p[32:32 + len(test_atom)] == test_atom
            assert intern(c, b'_LUDASHI_ABSENT_ATOM', True) == 0

            # An oversized reply must discard its staged header before the
            # X error. The following variable-length reply stays well formed.
            for mode in (0, 2):
                body = struct.pack('<IIIB3xI', c.root, atom, 31, 8, 8 * 1024 * 1024)
                body += b'x' * (8 * 1024 * 1024)
                c.seq += 1
                c.sock.sendall(struct.pack('<BBHI', 18, mode, 0, (len(body) + 8) // 4) + body)
                c.barrier()
            c.send(20, payload=struct.pack('<IIIII', c.root, atom, 31, 0, 4 * 1024 * 1024))
            error = c.packet()
            assert error[0] == 0 and error[1] == 11, error.hex()
            p = c.reply(c.send(17, payload=struct.pack('<I', atom)))
            assert p[32:32 + len(test_atom)] == test_atom
            c.send(19, payload=struct.pack('<II', c.root, atom))
            c.barrier()

            # Releasing a selection twice sends one clear event. The following
            # reply must arrive without a second clear event.
            c.send(22, payload=struct.pack('<III', win, atom, 0))
            selection = struct.pack('<I', atom)
            p = c.reply(c.send(23, payload=selection))
            assert struct.unpack_from('<I', p, 8)[0] == win
            for _ in range(2):
                c.send(22, payload=struct.pack('<III', 0, atom, 0))
            clear = c.packet()
            assert clear[0] == 29 and struct.unpack_from('<II', clear, 8) == (win, atom)
            seq = c.send(23, payload=selection)
            p = c.packet()
            assert p[0] == 1 and struct.unpack_from('<H', p, 2)[0] == seq
            assert struct.unpack_from('<I', p, 8)[0] == 0

            # Dropping an owner window must clear all of its selections while
            # leaving other claims reachable after the active list compacts.
            temporary = Client(path)
            other_win = temporary.base | 1
            temporary.send(1, payload=struct.pack('<IIhhHHHHII', other_win, c.root, 0, 0, 8, 8, 0, 1, 0, 0))
            temporary.send(22, payload=struct.pack('<III', other_win, 1, 0))
            temporary.barrier()
            c.send(22, payload=struct.pack('<III', win, atom, 0))
            c.barrier()
            temporary.send(22, payload=struct.pack('<III', other_win, 2, 0))
            temporary.send(4, payload=struct.pack('<I', other_win))
            temporary.barrier()
            for selected, expected in ((1, 0), (2, 0), (atom, win)):
                reply = c.reply(c.send(23, payload=struct.pack('<I', selected)))
                assert struct.unpack_from('<I', reply, 8)[0] == expected, reply.hex()
            temporary.close()

            # A burst of replies larger than the socket buffer must preserve
            # sequence order and leave another client's requests runnable.
            slow = Client(path)
            assert intern(slow, test_atom, True) == atom
            keymap = struct.pack('<BBHBBH', 101, 0, 2, 8, 248, 0)
            slow.sock.sendall(keymap * 256)
            c.barrier()
            for sequence in range(2, 258):
                p = slow.packet()
                assert p[0] == 1 and struct.unpack_from('<H', p, 2)[0] == sequence
                assert len(p) == 32 + 248 * 8
            slow.close()

            # RandR exposes a connected fixed-resolution output and a mode.
            p=c.reply(c.send(extensions['RANDR'],8,struct.pack('<I',c.root)))
            assert struct.unpack_from('<HHH',p,16)==(1,1,1)
            # AwaitFence must block only that client, and resume when another client triggers it.
            fence=c.base|4;sync=extensions['SYNC']
            c.send(sync,14,struct.pack('<II',win,fence)+b'\0'*4)
            c.send(sync,19,struct.pack('<I',fence))
            blocked=c.send(43)
            other=Client(path);other.send(sync,15,struct.pack('<I',fence));other.barrier();c.reply(blocked)
            # Match Java: IDLE then COPY Complete after the scene update, independently of Draw.
            present=extensions['Present'];eid=c.base|5
            c.send(present,3,struct.pack('<III',eid,win,6));c.barrier()
            # Include valid and update regions in the wire request.
            def submit(pixmap,serial):
                return c.send(present,1,struct.pack('<IIIIIhhIIIIIQQQ',win,pixmap,serial,0,0,0,0,0,0,0,0,0,0,0,0))
            def acknowledge(serial):
                for kind in (2,1):
                    e=c.packet()
                    assert e[0]==35 and struct.unpack_from('<H',e,8)[0]==kind,e.hex()
                    assert struct.unpack_from('<I',e,20)[0]==serial,e.hex()
                    if kind==1:assert e[11]==0,e.hex() # COPY, including coalesced Presents.
            submit(pix,10)
            acknowledge(10)
            pix2=c.base|6;c.send(53,24,struct.pack('<IIHH',pix2,win,64,64));submit(pix2,11)
            acknowledge(11)
            # Combined writes must still honor IDLE-only / Complete-only masks
            # and leave the next reply exactly at its packet boundary.
            for mask,kinds in [(4,(2,)),(2,(1,)),(6,(2,1))]:
                c.send(present,3,struct.pack('<III',eid,win,mask));c.barrier()
                submit(pix,50+mask)
                for kind in kinds:
                    e=c.packet()
                    assert e[0]==35 and struct.unpack_from('<H',e,8)[0]==kind,e.hex()
                    assert struct.unpack_from('<I',e,20)[0]==50+mask,e.hex()
                barrier=c.send(43);e=c.packet()
                assert e[0]==1 and struct.unpack_from('<H',e,2)[0]==barrier,e.hex()
            extra_eid=c.base|30
            c.send(present,3,struct.pack('<III',extra_eid,win,6));c.barrier()
            submit(pix2,60)
            seen=[];timestamps=[]
            for _ in range(4):
                e=c.packet();assert e[0]==35,e.hex()
                seen.append((struct.unpack_from('<I',e,12)[0],struct.unpack_from('<H',e,8)[0]))
                if seen[-1][1]==1:timestamps.append(struct.unpack_from('<QQ',e,24))
            assert seen==[(eid,2),(eid,1),(extra_eid,2),(extra_eid,1)],seen
            assert timestamps[0]==timestamps[1],timestamps
            c.send(present,3,struct.pack('<III',extra_eid,win,0));c.barrier()
            # DRI3 receives an AHB handle on the passed socket, not pixels on X11.
            gpu=c.base|21
            transfer,producer=socket.socketpair()
            producer.settimeout(3)
            payload=struct.pack('<IIIHHHBB',gpu,win,64*64*4,64,64,256,24,32)
            packet=struct.pack('<BBH',extensions['DRI3'],2,6)+payload
            c.seq+=1
            c.sock.sendmsg([packet],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,array.array('i',[transfer.fileno()]))])
            transfer.close()
            # The renderer must signal readiness before the producer sends its AHB.
            # With the old implementation this receives no byte and times out.
            assert exact(producer, 1) == b'\x01'
            producer.sendall(struct.pack('<IIIIQIIQ',64,64,1,1,0,64,0,0))
            producer.close();c.barrier()
            submit(gpu,12)
            acknowledge(12)
            # Every producer request receives exactly one COPY and IDLE even
            # when the compositor coalesces most pending scene updates.
            for serial in range(100,500):submit([pix,pix2,gpu][serial%3],serial)
            barrier=c.send(43);complete=0;idle=0
            while True:
                e=c.packet()
                if e[0]==1 and struct.unpack_from('<H',e,2)[0]==barrier:break
                assert e[0]!=0,e.hex()
                if e[0]==35:
                    kind=struct.unpack_from('<H',e,8)[0]
                    if kind==1:assert e[11]==0,e.hex();complete+=1
                    elif kind==2:idle+=1
            assert (complete,idle)==(400,400),(complete,idle)
            # Free displayed resources and disconnect, then reconnect repeatedly.
            c.send(54,payload=struct.pack('<I',pix));c.send(4,payload=struct.pack('<I',win));c.barrier();c.close();other.close()
            for _ in range(150):
                peer=Client(path);peer.barrier();peer.close()
            # Unknown opcodes are X errors and do not crash the entire Android process.
            peer=Client(path);peer.send(126);assert peer.packet()[0]==0;peer.barrier();peer.close()
            # Framing checks apply to each request span, including when the
            # next request is already buffered immediately after it.
            peer = Client(path)
            malformed = [
                (16, 0, struct.pack('<HH', 500, 0), 16),
                (14, 0, b'', 16),
                (55, 0, struct.pack('<III', peer.base | 1, peer.root, 4), 16),
                (2, 0, struct.pack('<II', peer.root, 2), 16),
                (98, 0, struct.pack('<HH', 400, 0), 16),
            ]
            for opcode, data, payload, error in malformed:
                seq = peer.send(opcode, data, payload)
                packet = peer.packet()
                assert packet[0] == 0 and packet[1] == error, packet.hex()
                assert struct.unpack_from('<H', packet, 2)[0] == seq
                peer.barrier()
            gc = peer.base | 2
            peer.send(55, payload=struct.pack('<III', gc, peer.root, 0))
            peer.barrier()
            peer.send(55, payload=struct.pack('<III', gc, peer.root, 0))
            packet = peer.packet()
            assert packet[0] == 0 and packet[1] == 14, packet.hex()
            peer.barrier()
            peer.close()
            # Deliberately incomplete requests must not monopolize epoll.
            peer=Client(path);peer.sock.sendall(b'\x01\x00');peer.close()
            peer=Client(path);peer.barrier();peer.close()
            assert process.poll() is None
            print('PASS: handshake/auth fragmentation, extensions, pixels, BIG-REQUESTS, keyboard, RandR, Sync, DRI3 AHB readiness/transfer, Java-compatible Present acknowledgements, 150 reconnects and malformed input')
        finally:
            process.terminate()
            try:process.wait(10)
            except subprocess.TimeoutExpired:process.kill();process.wait();raise
            log.seek(0);output=log.read();log.close()
            assert process.returncode==0,output
            assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output,output

if __name__=='__main__':run(sys.argv[1])
