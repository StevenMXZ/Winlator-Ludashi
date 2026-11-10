#!/usr/bin/env python3
"""Unread requests behind AwaitFence must not spin the epoll thread."""
import os
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import time
from test_wire import Client

def cpu(pid):
    fields=pathlib.Path(f'/proc/{pid}/stat').read_text().split()
    return (int(fields[13])+int(fields[14]))/os.sysconf('SC_CLK_TCK')

def run(binary):
    channel,child=socket.socketpair()
    with tempfile.TemporaryFile(mode='w+') as log:
        process=subprocess.Popen([binary,str(child.fileno())],pass_fds=[child.fileno()],
            stdout=subprocess.PIPE,stderr=log,text=True)
        child.close()
        try:
            assert process.stdout.readline().strip()=='ready'
            c=Client(channel); observer=Client(channel); sync=c.extension('SYNC')
            fence=c.base|1
            c.send(sync,14,struct.pack('<II',c.root,fence)+bytes(4)); c.barrier()
            for cycle in range(3):
                c.send(sync,16,struct.pack('<I',fence)) # ResetFence
                c.barrier()
                c.send(sync,19,struct.pack('<I',fence))
                time.sleep(.06) # Let epoll consume AwaitFence without later bytes.
                sequence=c.send(43) # A new readable packet must remain blocked.
                c.sock.settimeout(.03)
                try: assert not c.sock.recv(1), 'AwaitFence replied before trigger'
                except socket.timeout: pass
                c.sock.settimeout(3)
                start=cpu(process.pid); time.sleep(.35); consumed=cpu(process.pid)-start
                assert consumed<.10, f'AwaitFence socket spin: server CPU={consumed:.3f}s / 0.35s'
                # Another client stays responsive and triggers buffered continuation.
                observer.barrier(); observer.send(sync,15,struct.pack('<I',fence)); observer.barrier()
                c.reply(sequence); c.barrier()
                print(f'PASS: AwaitFence cycle={cycle} queued input sleeps (CPU={consumed:.3f}s), other client progresses, trigger resumes input',flush=True)
            # A suspended client can still disconnect and release resources.
            c.send(sync,16,struct.pack('<I',fence)); c.barrier()
            c.send(sync,19,struct.pack('<I',fence)); time.sleep(.03); c.close()
            observer.barrier(); observer.close()
        finally:
            process.terminate();process.wait(10);channel.close()
            log.seek(0);output=log.read()
            assert process.returncode==0,output
            assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output,output

if __name__=='__main__':run(sys.argv[1])
