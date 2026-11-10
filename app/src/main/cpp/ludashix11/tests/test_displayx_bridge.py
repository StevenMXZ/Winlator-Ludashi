#!/usr/bin/env python3
"""DisplayX+ wire compatibility, presentation completion and window lifetimes."""
import array
import os
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import time
from test_wire import Client, exact


def connect():
    peer = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    peer.settimeout(3)
    peer.connect('\0displayx')
    return peer


def swapchain(peer, window, mode=2):
    peer.sendall(struct.pack('<IBIIIII', 1, 7, 3, window, mode, 37, 0))
    # The host AHB shim uses a descriptor in place of Android's handle transfer.
    for _ in range(3):
        peer.sendall(struct.pack('<IIIIQIIQ', 32, 32, 1, 1, 0, 32, 0, 0))


def present(peer, index, serial):
    read_fd, write_fd = os.pipe()
    try:
        os.write(write_fd, b'1')
        peer.sendmsg([struct.pack('<IBIQ', 3, 7, index, serial)], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [read_fd]))])
    finally:
        os.close(read_fd)
        os.close(write_fd)


def completion(peer):
    opcode, count, chain, serial, indexes, index = struct.unpack('<IIBQII', peer.recv(65536))
    assert opcode == 4 and count == 1 and chain == 7 and indexes == 1
    return serial, index


def run(binary):
    with tempfile.TemporaryDirectory(prefix='ludashi-displayx-') as directory:
        channel, child = socket.socketpair()
        log_path = pathlib.Path(directory) / 'server.log'
        with log_path.open('w') as log:
            process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                stdout=subprocess.PIPE, stderr=log, text=True,
                env={**os.environ, 'LUDASHI_HOST_DISPLAYX': '1', 'LUDASHI_HOST_FENCE_DELAY_MS': '150'})
            child.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                client = Client(channel)
                window = client.base | 1
                def create():
                    client.send(1, payload=struct.pack('<IIhhHHHHII', window, client.root, 0, 0, 32, 32, 0, 1, 0, 0))
                    client.send(8, payload=struct.pack('<I', window))
                    client.barrier()
                create()
                peer = connect()
                swapchain(peer, window)
                start = time.monotonic()
                present(peer, 0, 100)
                assert completion(peer) == (100, 0)
                assert time.monotonic() - start >= .06, 'FIFO completion preceded the presentation fence'
                start = time.monotonic()
                for serial in range(101, 104):
                    present(peer, serial % 3, serial)
                assert {completion(peer)[0] for _ in range(3)} == {101, 102, 103}
                assert time.monotonic() - start < .4, 'FIFO submission serialized presentation instead of pipelining it'
                client.send(10, payload=struct.pack('<I', window)); client.barrier()
                present(peer, 0, 104)
                peer.settimeout(.05)
                try:
                    peer.recv(65536)
                    raise AssertionError('hidden FIFO frame was acknowledged before it could be presented')
                except socket.timeout:
                    pass
                peer.settimeout(3)
                client.send(8, payload=struct.pack('<I', window)); client.barrier()
                assert completion(peer) == (104, 0)
                peer.sendall(struct.pack('<IB', 2, 7))
                swapchain(peer, window, 1)
                for serial in range(200, 218):
                    present(peer, serial % 3, serial)
                returned = {completion(peer)[0] for _ in range(18)}
                assert returned == set(range(200, 218)), returned
                # An XID can be reused, but a previous swapchain cannot bind the new window.
                client.send(4, payload=struct.pack('<I', window)); client.barrier()
                create()
                present(peer, 0, 300)
                while peer.recv(128):
                    pass
                peer.close()
                peer = connect(); swapchain(peer, window)
                present(peer, 0, 400)
                assert completion(peer) == (400, 0)
                # Invalid image indices close the peer and its received fence, leaving X11 live.
                present(peer, 99, 500)
                while peer.recv(128):
                    pass
                peer.close(); client.barrier(); client.close()
                print('PASS: DisplayX+ packets/AHBs/fences, FIFO completion, mailbox image release, XID reuse and malformed peers')
            finally:
                process.terminate(); process.wait(10); channel.close()
                assert process.returncode == 0, log_path.read_text()
                output = log_path.read_text()
                assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output


if __name__ == '__main__':
    run(sys.argv[1])
