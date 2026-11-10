#!/usr/bin/env python3
"""A stalled DRI3 sender suspends only its own stream, including on teardown."""
import array
import socket
import struct
import subprocess
import sys
import tempfile
from test_wire import Client, exact


def run(binary):
    channel, child = socket.socketpair()
    with tempfile.TemporaryFile(mode='w+') as log:
        process = subprocess.Popen([binary, str(child.fileno())], pass_fds=[child.fileno()],
                                   stdout=subprocess.PIPE, stderr=log, text=True)
        child.close()
        try:
            assert process.stdout.readline().strip() == 'ready'
            other = Client(channel)

            def begin(client, ident):
                opcode = client.extension('DRI3')
                transfer, producer = socket.socketpair()
                producer.settimeout(2)
                payload = struct.pack('<IIIHHHBB', client.base | ident, client.root,
                                      16384, 64, 64, 256, 24, 32)
                client.seq += 1
                client.sock.sendmsg([struct.pack('<BBH', opcode, 2, 6) + payload],
                    [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [transfer.fileno()]))])
                transfer.close()
                assert exact(producer, 1) == b'\x01'
                return producer, client.seq

            client = Client(channel)
            producer, sequence = begin(client, 1)
            # This reply must arrive while the first client has not sent its AHB.
            other.barrier()
            barrier = client.send(43)
            client.sock.settimeout(.05)
            try:
                client.sock.recv(1, socket.MSG_PEEK)
                raise AssertionError('request after import ran before AHB receipt')
            except socket.timeout:
                pass
            client.sock.settimeout(3)
            producer.sendall(struct.pack('<IIIIQIIQ', 64, 64, 1, 1, 0, 64, 0, 0))
            producer.close()
            client.reply(barrier)
            # The successfully imported pixmap is available to the next request.
            pixels = client.reply(client.send(73, 2,
                struct.pack('<IhhHHI', client.base | 1, 0, 0, 1, 1, 0xffffffff)))
            assert len(pixels) == 36

            producer, sequence = begin(client, 2)
            producer.close()  # Failed transfer produces the original request's error.
            error = client.packet()
            assert error[0] == 0 and error[1] == 8 and struct.unpack_from('<H', error, 2)[0] == sequence
            client.barrier()
            client.close()

            # Destroy a client blocked in handle receipt; its worker must cancel.
            for _ in range(8):
                client = Client(channel)
                producer, _ = begin(client, 1)
                client.close()
                other.barrier()
                producer.close()
            other.close()
            print('PASS: stalled DRI3 import leaves other clients responsive; ordering, errors and cancellation preserved')
        finally:
            process.terminate()
            process.wait(10)
            channel.close()
            log.seek(0)
            output = log.read()
            assert process.returncode == 0, output
            assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output


if __name__ == '__main__':
    run(sys.argv[1])
