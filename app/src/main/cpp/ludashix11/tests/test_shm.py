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
import time
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
        replacement = os.memfd_create('winlator-shm-replacement-test', os.MFD_CLOEXEC)
        os.write(replacement, struct.pack('<I', 0xabcdef) * 64 * 64)
        empty = os.memfd_create('winlator-shm-empty-test', os.MFD_CLOEXEC)
        commands, requested_ids, failures = [], [], []

        def service():
            try:
                while True:
                    request = exact(shm, 5)
                    command, shmid = struct.unpack('<BI', request)
                    commands.append(command)
                    requested_ids.append(shmid)
                    # Like SysVSHMRequestHandler: no GET_SIZE command or response.
                    if command == 1:
                        descriptor = {1: fd, 2: replacement, 3: empty}.get(shmid)
                        if descriptor is not None:
                            shm.sendmsg([b'\0'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS,
                                                  array.array('i', [descriptor]))])
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
                def mappings():
                    return sum('memfd:winlator-shm-' in line
                               for line in pathlib.Path(f'/proc/{process.pid}/maps').read_text().splitlines())

                def pixel(segment_id, expected):
                    client.send(opcode, 3, struct.pack('<IIHHHHHHhhBBBBII',
                        win, gc, 64, 64, 0, 0, 64, 64, 0, 0, 24, 2, 0, 0, segment_id, 0))
                    image = client.reply(client.send(73, 2,
                        struct.pack('<IhhHHI', win, 0, 0, 1, 1, 0xffffffff)))
                    assert struct.unpack_from('<I', image, 32)[0] & 0xffffff == expected

                alias = client.base | 4
                client.send(opcode, 1, struct.pack('<IIB3x', alias, 1, 0))
                client.barrier()
                assert requested_ids == [1, 1] and mappings() == 1, requested_ids

                # SHM and ordinary resources share the client's XID namespace.
                client.send(53, 24, struct.pack('<IIHH', alias, win, 64, 64))
                error = client.packet()
                assert error[0] == 0 and error[1] == 14, error.hex()
                client.send(opcode, 1, struct.pack('<IIB3x', win, 1, 0))
                error = client.packet()
                assert error[0] == 0 and error[1] == 14, error.hex()
                client.barrier()
                pixel(alias, 0x123456)
                # A rejected window ID must leave both tree and listeners intact.
                client.send(1, payload=struct.pack('<IIhhHHHHIII',
                    alias, client.root, 0, 0, 64, 64, 0, 1, 0, 1 << 11, 3))
                error = client.packet()
                assert error[0] == 0 and error[1] == 14, error.hex()
                tree = client.reply(client.send(15, payload=struct.pack('<I', client.root)))
                assert struct.unpack_from('<H', tree, 16)[0] == 1
                assert struct.unpack_from('<I', tree, 32)[0] == win
                pixel(alias, 0x123456)

                # Replacing one alias retains the other alias's original mapping.
                client.send(opcode, 1, struct.pack('<IIB3x', segment, 2, 0))
                client.barrier()
                assert requested_ids == [1, 1, 2] and mappings() == 2, requested_ids
                pixel(segment, 0xabcdef)
                pixel(alias, 0x123456)
                client.send(opcode, 2, struct.pack('<I', segment))
                client.barrier()
                assert mappings() == 1

                # Failed replacement preserves the old attachment. Empty
                # regions and absent descriptors both yield BadAccess.
                for system_id in (99, 3):
                    client.send(opcode, 1, struct.pack('<IIB3x', alias, system_id, 0))
                    error = client.packet()
                    assert error[0] == 0 and error[1] == 10, error.hex()
                    pixel(alias, 0x123456)

                # Invalid segment errors preserve the connection.
                client.send(opcode, 2, struct.pack('<I', segment))
                error = client.packet()
                assert error[0] == 0 and error[1] == 128, error.hex()
                client.barrier()

                # The descriptor can close once mmap succeeds.
                links = []
                for entry in pathlib.Path(f'/proc/{process.pid}/fd').iterdir():
                    try: links.append(os.readlink(entry))
                    except FileNotFoundError: pass
                assert not any('memfd:winlator-shm-' in target for target in links), links
                client.close()
                deadline = time.monotonic() + 2
                while mappings() and time.monotonic() < deadline:
                    time.sleep(.005)
                assert mappings() == 0, 'disconnect retained an SHM mapping'
                print('PASS: GET_FD transport, alias reuse, atomic replacement/failure, pixels, invalid segments, FD release and disconnect cleanup')
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
                os.close(replacement)
                os.close(empty)
                log.seek(0)
                output = log.read()
                assert process.returncode == 0, output
                assert not failures, failures
                assert 'ERROR: AddressSanitizer' not in output and 'runtime error:' not in output, output


if __name__ == '__main__':
    run(sys.argv[1])
