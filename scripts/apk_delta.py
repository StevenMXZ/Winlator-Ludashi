#!/usr/bin/env python3
"""Small in-app updates: rebuild the new APK from the installed one plus a patch.

The new APK is described as COPY ranges (zip entries whose raw, still-compressed
bytes already sit in the old APK) and LITERAL bytes (changed entries, local
headers, the signing block, the central directory). Nothing is recompressed, so
the rebuild is byte-exact and the APK signature stays valid. The app side is
core/ApkPatcher (same format); release.yml makes and checks the patches.

Format BLDP v1, all integers little-endian:
    0   4   magic b"BLDP"
    4   2   format version (1)
    6   2   flags (0)
    8   8   old size
    16  32  old sha256
    48  8   new size
    56  32  new sha256
    88  4   op count
    92  17 * op count: u8 type (1 = COPY, 2 = LITERAL), u64 a, u64 b
        COPY: a = offset in the old APK, b = length
        LITERAL: a = length, b = 0 (bytes come from the literal stream, in op order)
    then one XZ stream (LZMA2, CRC64) holding every literal byte back to back.

    apk_delta.py make OLD NEW PATCH     write PATCH, print a one-line summary
    apk_delta.py apply OLD PATCH OUT    rebuild, exit 1 unless OUT has the new sha256
"""
import hashlib, lzma, mmap, struct, sys, zipfile
from pathlib import Path

MAGIC = b"BLDP"
VERSION = 1
HEADER = struct.Struct("<4sHHQ32sQ32sI")
OP = struct.Struct("<BQQ")
COPY, LITERAL = 1, 2
CHUNK = 1 << 20


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while b := f.read(CHUNK):
            h.update(b)
    return h.digest()


def entry_ranges(path, data):
    """(data offset, compressed length) of every non-empty entry, in file order."""
    out = []
    with zipfile.ZipFile(path) as z:
        for i in z.infolist():
            o = i.header_offset
            if data[o:o + 4] != b"PK\x03\x04":
                raise ValueError(f"{path}: bad local header for {i.filename}")
            n, e = struct.unpack_from("<HH", data, o + 26)
            if i.compress_size:
                out.append((o + 30 + n + e, i.compress_size))
    return sorted(out)


def make(old, new, patch):
    with open(old, "rb") as fo, open(new, "rb") as fn, \
         mmap.mmap(fo.fileno(), 0, access=mmap.ACCESS_READ) as od, \
         mmap.mmap(fn.fileno(), 0, access=mmap.ACCESS_READ) as nd:
        old_by_hash = {}
        for off, ln in entry_ranges(old, od):
            old_by_hash.setdefault(hashlib.sha256(od[off:off + ln]).digest(), off)

        ops, lits, cur = [], [], 0
        for off, ln in entry_ranges(new, nd):
            src = old_by_hash.get(hashlib.sha256(nd[off:off + ln]).digest())
            if src is None:
                continue
            if off > cur:
                ops.append((LITERAL, off - cur, 0)); lits.append((cur, off))
            if ops and ops[-1][0] == COPY and ops[-1][1] + ops[-1][2] == src:
                ops[-1] = (COPY, ops[-1][1], ops[-1][2] + ln)   # contiguous in both files
            else:
                ops.append((COPY, src, ln))
            cur = off + ln
        if cur < len(nd):
            ops.append((LITERAL, len(nd) - cur, 0)); lits.append((cur, len(nd)))

        comp = lzma.LZMACompressor(format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, preset=6)
        blob = b"".join([comp.compress(nd[a:b]) for a, b in lits] + [comp.flush()])
        old_len, new_len = len(od), len(nd)

    header = HEADER.pack(MAGIC, VERSION, 0, old_len, sha256_file(old), new_len, sha256_file(new), len(ops))
    with open(patch, "wb") as p:
        p.write(header)
        for op in ops:
            p.write(OP.pack(*op))
        p.write(blob)
    copied = sum(op[2] for op in ops if op[0] == COPY)
    literal = sum(op[1] for op in ops if op[0] == LITERAL)
    size = HEADER.size + OP.size * len(ops) + len(blob)
    print(f"ops={len(ops)} copied={copied / 1e6:.1f}MB literal={literal / 1e6:.2f}MB patch={size / 1e6:.2f}MB")


def apply(old, patch, out):
    with open(patch, "rb") as p:
        magic, ver, _flags, old_len, old_sha, new_len, new_sha, n = HEADER.unpack(p.read(HEADER.size))
        if magic != MAGIC or ver != VERSION:
            raise ValueError("not a BLDP v1 patch")
        if Path(old).stat().st_size != old_len or sha256_file(old) != old_sha:
            raise ValueError("installed APK does not match patch")
        ops = [OP.unpack(p.read(OP.size)) for _ in range(n)]
        lit = lzma.LZMADecompressor(format=lzma.FORMAT_XZ)
        pending = b""

        def literal(count):
            nonlocal pending
            while len(pending) < count:
                if lit.eof:
                    raise ValueError("literal stream ended early")
                data = p.read(CHUNK) if lit.needs_input else b""
                if lit.needs_input and not data:
                    raise ValueError("patch is truncated")
                pending += lit.decompress(data, max_length=CHUNK)
            chunk, pending = pending[:count], pending[count:]
            return chunk

        h = hashlib.sha256()
        with open(old, "rb") as o, open(out, "wb") as w:
            for kind, a, b in ops:
                if kind == COPY:
                    o.seek(a)
                    while b:
                        chunk = o.read(min(b, CHUNK)); b -= len(chunk)
                        if not chunk:
                            raise ValueError("copy past the end of the old APK")
                        w.write(chunk); h.update(chunk)
                elif kind == LITERAL:
                    while a:
                        chunk = literal(min(a, CHUNK)); a -= len(chunk)
                        w.write(chunk); h.update(chunk)
                else:
                    raise ValueError(f"unknown op {kind}")
        if pending:
            raise ValueError("unused literal bytes")
        while not lit.eof:
            chunk = p.read(CHUNK) if lit.needs_input else b""
            if lit.needs_input and not chunk:
                raise ValueError("patch is truncated")
            if lit.decompress(chunk, max_length=CHUNK):
                raise ValueError("unused literal bytes")
    ok = h.digest() == new_sha and Path(out).stat().st_size == new_len
    print(f"rebuilt sha256 {h.hexdigest()} {'MATCHES' if ok else 'MISMATCH'}")
    return ok


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    if cmd == "make":
        make(*args)
    elif cmd == "apply":
        sys.exit(0 if apply(*args) else 1)
    else:
        sys.exit(__doc__)
