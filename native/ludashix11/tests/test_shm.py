#!/usr/bin/env python3
"""Exercise Wine's MIT-SHM attach/upload against Winlator's actual GET_FD protocol."""
import array
import os
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import threading
from test_wire import Client, exact


def run(binary):
    result = subprocess.run([binary, '--disconnected-output'], capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    with tempfile.TemporaryDirectory(prefix='ludashix11-shm-') as directory:
        channel, peer = socket.socketpair()
        shm, shm_peer = socket.socketpair()
        pixels = struct.pack('<I', 0x123456) * 64 * 64
        fd = os.memfd_create('winlator-shm-test', os.MFD_CLOEXEC)
        os.ftruncate(fd, len(pixels))
        os.write(fd, pixels)
        commands, failures = [], []

        def service():
            try:
                while True:
                    request = exact(shm, 5)
                    command, shmid = struct.unpack('<BI', request)
                    commands.append(command)
                    # Like SysVSHMRequestHandler: no GET_SIZE command or response.
                    if command == 1:
                        if shmid == 1:
                            shm.sendmsg([b'\0'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS,
                                                  array.array('i', [fd]))])
                        else:
                            shm.sendall(b'\0')  # Unknown segment: no descriptor.
            except AssertionError:
                pass  # The native side has closed its transport.
            except Exception as error:
                failures.append(error)

        thread = threading.Thread(target=service, daemon=True)
        thread.start()
        with open(pathlib.Path(directory) / 'server.log', 'w+') as log:
            process = subprocess.Popen([binary, str(peer.fileno()), str(shm_peer.fileno())],
                pass_fds=[peer.fileno(), shm_peer.fileno()], stdout=subprocess.PIPE,
                stderr=log, text=True)
            peer.close()
            shm_peer.close()
            try:
                assert process.stdout.readline().strip() == 'ready'
                client = Client(channel)
                opcode = client.extension('MIT-SHM')
                win, gc, segment = client.base | 1, client.base | 2, client.base | 3
                client.send(1, payload=struct.pack('<IIhhHHHHII',
                    win, client.root, 0, 0, 64, 64, 0, 1, 0, 0))
                client.send(8, payload=struct.pack('<I', win))
                client.send(55, payload=struct.pack('<III', gc, win, 0))
                client.send(opcode, 1, struct.pack('<IIB3x', segment, 1, 0))
                client.barrier()
                assert commands == [1], commands
                # XShmPutImage: exercise the desktop pixel path rather than just QueryVersion.
                client.send(opcode, 3, struct.pack('<IIHHHHHHhhBBBBII',
                    win, gc, 64, 64, 0, 0, 64, 64, 0, 0, 24, 2, 0, 0, segment, 0))
                image = client.reply(client.send(73, 2,
                    struct.pack('<IhhHHI', win, 0, 0, 1, 1, 0xffffffff)))
                assert struct.unpack_from('<I', image, 32)[0] & 0xffffff == 0x123456
                # Detach/re-attach must work on the persistent service connection.
                client.send(opcode, 2, struct.pack('<I', segment))
                client.send(opcode, 1, struct.pack('<IIB3x', segment, 1, 0))
                client.barrier()
                assert commands == [1, 1], commands
                # Missing descriptors yield BadAccess, preserving the X11 client.
                client.send(opcode, 1, struct.pack('<IIB3x', client.base | 4, 99, 0))
                error = client.packet()
                assert error[0] == 0 and error[1] == 10, error.hex()
                client.barrier()
                client.send(opcode, 2, struct.pack('<I', segment))
                client.barrier()
                client.close()
                print('PASS: disconnected output, Winlator GET_FD-only MIT-SHM attach, pixels, reattach and invalid descriptor')
            finally:
                process.terminate()
                try:
                    process.wait(5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                channel.close()
                thread.join(2)
                shm.close()
                os.close(fd)
                log.seek(0)
                output = log.read()
                assert process.returncode == 0, output
                assert not failures, failures
                assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output


if __name__ == '__main__':
    run(sys.argv[1])
