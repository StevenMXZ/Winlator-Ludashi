package com.winlator.cmod.components.offline;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the streams of a Compound File (the container a Windows Installer package is: an .msi is
 * a small FAT file system of named streams). Only what a package needs: the streams directly in
 * the root storage, read on demand from the file, so a package of several hundred MB with its
 * cabinet inside never has to fit in memory.
 */
public final class CompoundFile implements Closeable {
    private static final long MAGIC = 0xE11AB1A1E011CFD0L;
    private static final int FREE = -1, END = -2, FAT_SECTOR = -3, DIFAT_SECTOR = -4;

    private final RandomAccessFile file;
    private final int sectorSize;
    private final int miniSectorSize;
    private final long miniCutoff;
    private final int[] fat;
    private int[] miniFat = new int[0];
    private int[] rootChain = new int[0];
    private final Entry root;
    private final Map<String, Entry> streams = new LinkedHashMap<>();

    /** A stream's directory entry: its (decoded later by the caller) name, first sector, size. */
    public static final class Entry {
        public final String name;
        final int type, left, right, child, start;
        public final long size;

        Entry(String name, int type, int left, int right, int child, int start, long size) {
            this.name = name; this.type = type; this.left = left; this.right = right;
            this.child = child; this.start = start; this.size = size;
        }
    }

    public CompoundFile(File path) throws IOException {
        file = new RandomAccessFile(path, "r");
        try {
            byte[] header = new byte[512];
            file.readFully(header);
            if (le64(header, 0) != MAGIC) throw new IOException("not a Windows Installer package");
            sectorSize = 1 << le16(header, 0x1E);
            miniSectorSize = 1 << le16(header, 0x20);
            if (sectorSize != 512 && sectorSize != 4096) throw new IOException("unexpected sector size " + sectorSize);
            int fatSectors = le32(header, 0x2C);
            int firstDir = le32(header, 0x30);
            miniCutoff = le32(header, 0x38) & 0xFFFFFFFFL;
            int firstMiniFat = le32(header, 0x3C);
            int firstDifat = le32(header, 0x44);
            int difatSectors = le32(header, 0x48);

            // Where the FAT's own sectors are: 109 in the header, the rest in a DIFAT chain.
            List<Integer> fatList = new ArrayList<>();
            for (int i = 0; i < 109 && fatList.size() < fatSectors; i++) {
                int s = le32(header, 0x4C + 4 * i);
                if (s >= 0) fatList.add(s);
            }
            int perSector = sectorSize / 4;
            int difat = firstDifat;
            byte[] sector = new byte[sectorSize];
            for (int n = 0; n < difatSectors && difat >= 0 && fatList.size() < fatSectors; n++) {
                readSector(difat, sector);
                for (int i = 0; i < perSector - 1 && fatList.size() < fatSectors; i++) {
                    int s = le32(sector, 4 * i);
                    if (s >= 0) fatList.add(s);
                }
                difat = le32(sector, sectorSize - 4);
            }
            fat = new int[fatList.size() * perSector];
            for (int i = 0; i < fatList.size(); i++) {
                readSector(fatList.get(i), sector);
                for (int j = 0; j < perSector; j++) fat[i * perSector + j] = le32(sector, 4 * j);
            }

            // The directory: 128-byte entries along the directory chain.
            byte[] dir = readChain(firstDir, -1);
            List<Entry> entries = new ArrayList<>();
            for (int at = 0; at + 128 <= dir.length; at += 128) {
                int nameLen = le16(dir, at + 0x40);
                String name = nameLen >= 2 ? new String(dir, at, Math.min(64, nameLen) - 2, StandardCharsets.UTF_16LE) : "";
                long size = le32(dir, at + 0x78) & 0xFFFFFFFFL;
                if (sectorSize == 4096) size |= (le32(dir, at + 0x7C) & 0xFFFFFFFFL) << 32;
                entries.add(new Entry(name, dir[at + 0x42] & 0xFF, le32(dir, at + 0x44), le32(dir, at + 0x48),
                        le32(dir, at + 0x4C), le32(dir, at + 0x74), size));
            }
            if (entries.isEmpty() || entries.get(0).type != 5) throw new IOException("package has no root storage");
            root = entries.get(0);
            if (firstMiniFat >= 0) {
                byte[] mf = readChain(firstMiniFat, -1);
                miniFat = new int[mf.length / 4];
                for (int i = 0; i < miniFat.length; i++) miniFat[i] = le32(mf, 4 * i);
            }
            List<Integer> chain = new ArrayList<>();
            for (int s = root.start, guard = 0; s >= 0 && s < fat.length && guard < fat.length; s = fat[s], guard++) chain.add(s);
            rootChain = new int[chain.size()];
            for (int i = 0; i < rootChain.length; i++) rootChain[i] = chain.get(i);
            // The root storage's children: a red-black tree by left/right siblings.
            collect(entries, root.child, 0);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    private void collect(List<Entry> entries, int index, int depth) {
        if (index < 0 || index >= entries.size() || depth > 4096) return;
        Entry e = entries.get(index);
        collect(entries, e.left, depth + 1);
        if (e.type == 2) streams.put(e.name, e);
        collect(entries, e.right, depth + 1);
    }

    /** The raw (still encoded) names of the root storage's streams. */
    public Iterable<Entry> entries() {
        return streams.values();
    }

    public Entry find(String rawName) {
        return streams.get(rawName);
    }

    public byte[] read(Entry e) throws IOException {
        if (e.size > Integer.MAX_VALUE - 16) throw new IOException("stream too large to read into memory: " + e.size);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) e.size);
        copy(e, out);
        return out.toByteArray();
    }

    /** Writes the stream's bytes to [target]. */
    public void extract(Entry e, File target) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            copy(e, out);
        }
    }

    private void copy(Entry e, OutputStream out) throws IOException {
        long left = e.size;
        if (e.size < miniCutoff) {
            // Small streams live in the mini stream (the root entry's chain), in 64-byte sectors.
            int s = e.start;
            byte[] buf = new byte[miniSectorSize];
            int guard = 0;
            while (left > 0 && s >= 0 && s < miniFat.length && guard++ < miniFat.length + 1) {
                long offset = (long) s * miniSectorSize;
                readRootStream(offset, buf);
                int n = (int) Math.min(left, miniSectorSize);
                out.write(buf, 0, n);
                left -= n;
                s = miniFat[s];
            }
        } else {
            int s = e.start;
            byte[] buf = new byte[sectorSize];
            int guard = 0;
            while (left > 0 && s >= 0 && s < fat.length && guard++ < fat.length + 1) {
                readSector(s, buf);
                int n = (int) Math.min(left, sectorSize);
                out.write(buf, 0, n);
                left -= n;
                s = fat[s];
            }
        }
        if (left > 0) throw new IOException("stream " + e.name + " is cut short");
    }

    // The root entry's chain holds the mini stream; reading one mini sector walks to it.
    private void readRootStream(long offset, byte[] buf) throws IOException {
        int index = (int) (offset / sectorSize);
        int within = (int) (offset % sectorSize);
        if (index >= rootChain.length) throw new IOException("mini stream is cut short");
        file.seek(sectorOffset(rootChain[index]) + within);
        file.readFully(buf, 0, buf.length);
    }

    private byte[] readChain(int start, long size) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[sectorSize];
        int s = start;
        int guard = 0;
        while (s >= 0 && s < fat.length && guard++ < fat.length + 1) {
            readSector(s, buf);
            out.write(buf, 0, buf.length);
            s = fat[s];
            if (size >= 0 && out.size() >= size) break;
        }
        return out.toByteArray();
    }

    private long sectorOffset(int s) {
        return (long) (s + 1) * sectorSize;
    }

    private void readSector(int s, byte[] buf) throws IOException {
        file.seek(sectorOffset(s));
        file.readFully(buf, 0, sectorSize);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    static long le64(byte[] b, int at) {
        return (le32(b, at) & 0xFFFFFFFFL) | ((long) le32(b, at + 4)) << 32;
    }
}
