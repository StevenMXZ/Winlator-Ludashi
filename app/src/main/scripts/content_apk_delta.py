#!/usr/bin/env python3
"""Ludashi content-aware APK delta (LDU1). Independent raw-byte chunk format.
Anchors are byte patterns, not ZIP positions, so unchanged chunks can move.
The complete command stream is compressed; the target is reconstructed byte-exact.
"""
import hashlib
import lzma
import mmap
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

MAGIC = b"LDU1"
HEADER = struct.Struct(">4sBQQ32s32s")
MIN, MAX = 16384, 262144
LIMIT = 2 * 1024**3
ANCHOR = b"\x00\xff"

def digest(path):
    with open(path, "rb") as f:
        return hashlib.file_digest(f, "sha256").digest()

def chunks(data):
    pos = 0
    while pos < len(data):
        end = min(pos + MAX, len(data))
        anchor = data.find(ANCHOR, pos + MIN, end)
        if anchor >= 0:
            end = anchor + len(ANCHOR)
        yield pos, end
        pos = end

def make(old, new, patch):
    if not (0 < os.path.getsize(old) <= LIMIT and 0 < os.path.getsize(new) <= LIMIT):
        raise ValueError("invalid APK size")
    with tempfile.TemporaryDirectory() as tmp:
        raw = Path(tmp) / "commands"
        with open(old, "rb") as a, open(new, "rb") as b, mmap.mmap(a.fileno(), 0, access=mmap.ACCESS_READ) as src, mmap.mmap(b.fileno(), 0, access=mmap.ACCESS_READ) as dst, raw.open("wb") as out:
            index = {}
            for start, end in chunks(src):
                index.setdefault(hashlib.sha256(src[start:end]).digest(), start)
            copied = 0
            for start, end in chunks(dst):
                block = dst[start:end]
                offset = index.get(hashlib.sha256(block).digest())
                if offset is not None and src[offset:offset+len(block)] == block:
                    out.write(struct.pack(">BQI", 1, offset, len(block)))
                    copied += len(block)
                else:
                    out.write(struct.pack(">BI", 2, len(block)))
                    out.write(block)
            out.write(b"\x00")
        candidates = []
        for codec, encoder in ((1, zlib.compressobj(9)), (2, lzma.LZMACompressor(preset=6))):
            target = Path(tmp) / str(codec)
            with raw.open("rb") as inp, target.open("wb") as out:
                while data := inp.read(1024*1024):
                    out.write(encoder.compress(data))
                out.write(encoder.flush())
            candidates.append((target.stat().st_size, codec, target))
        if shutil.which("zstd"):
            target = Path(tmp) / "3"
            subprocess.run(["zstd", "-q", "-T2", "-15", "-f", str(raw), "-o", str(target)], check=True)
            candidates.append((target.stat().st_size, 3, target))
        _, codec, payload = min(candidates)
        with open(patch, "wb") as out, payload.open("rb") as inp:
            out.write(HEADER.pack(MAGIC, codec, os.path.getsize(old), os.path.getsize(new), digest(old), digest(new)))
            shutil.copyfileobj(inp, out)
        print(f"LDU1 codec={codec} reused={copied} patch={os.path.getsize(patch)} target={os.path.getsize(new)}")

def apply(old, patch, out):
    with open(patch, "rb") as f, tempfile.TemporaryDirectory() as tmp:
        magic, codec, old_size, new_size, old_sha, new_sha = HEADER.unpack(f.read(HEADER.size))
        if magic != MAGIC or old_size != os.path.getsize(old) or digest(old) != old_sha or not 0 < new_size <= LIMIT:
            raise ValueError("invalid base or header")
        raw = Path(tmp) / "commands"
        if codec == 3:
            compressed = Path(tmp) / "zstd"
            with compressed.open("wb") as w:
                shutil.copyfileobj(f, w)
            subprocess.run(["zstd", "-q", "-d", "-f", str(compressed), "-o", str(raw)], check=True)
        else:
            decoder = zlib.decompressobj() if codec == 1 else lzma.LZMADecompressor() if codec == 2 else None
            if decoder is None:
                raise ValueError("invalid codec")
            with raw.open("wb") as w:
                while data := f.read(65536):
                    w.write(decoder.decompress(data))
                if not decoder.eof or decoder.unused_data:
                    raise ValueError("invalid compressed stream")
        with raw.open("rb") as cmd, open(old, "rb") as src, open(out, "wb") as dst:
            written = 0
            while True:
                kind = cmd.read(1)
                if kind == b"\x00":
                    break
                if kind == b"\x01":
                    offset, length = struct.unpack(">QI", cmd.read(12))
                    if offset + length > old_size:
                        raise ValueError("copy outside base")
                    src.seek(offset)
                    data = src.read(length)
                elif kind == b"\x02":
                    length, = struct.unpack(">I", cmd.read(4))
                    data = cmd.read(length)
                else:
                    raise ValueError("invalid operation")
                if not 0 < length <= MAX or len(data) != length or written + length > new_size:
                    raise ValueError("invalid length")
                dst.write(data)
                written += length
            if cmd.read(1) or written != new_size:
                raise ValueError("invalid target size")
        if digest(out) != new_sha:
            raise ValueError("target checksum mismatch")

if __name__ == "__main__":
    action, old, second, third = sys.argv[1:]
    {"make": make, "apply": apply}[action](old, second, third)
