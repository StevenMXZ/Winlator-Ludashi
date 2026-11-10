package com.winlator.cmod.components.offline;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


public final class MsiDatabase implements Closeable {
    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._";
    private static final int TYPE_VALID = 0x0100, TYPE_STRING = 0x0800, TYPE_NULLABLE = 0x1000;

    private final CompoundFile cf;
    private final Map<String, CompoundFile.Entry> byName = new HashMap<>();
    private final List<String> strings = new ArrayList<>();
    private int strRefBytes = 2;
    private final Set<String> tableNames = new LinkedHashSet<>();
    private final Map<String, List<Column>> columns = new HashMap<>();
    private final Map<String, List<Map<String, String>>> cache = new HashMap<>();
    public final Map<String, String> summary = new HashMap<>();
    public final String path;

    private static final class Column {
        final String name;
        final int type;

        Column(String name, int type) {
            this.name = name; this.type = type;
        }

        int width(int strRef) {
            if ((type & ~TYPE_NULLABLE) == (TYPE_STRING | TYPE_VALID)) return 2;  // a binary (stream) column
            if ((type & TYPE_STRING) != 0) return strRef;
            return (type & 0xFF) <= 2 ? 2 : 4;
        }

        boolean isString() {
            return (type & TYPE_STRING) != 0 && (type & ~TYPE_NULLABLE) != (TYPE_STRING | TYPE_VALID);
        }
    }

    public MsiDatabase(File file) throws IOException {
        path = file.getPath();
        cf = new CompoundFile(file);
        try {
            for (CompoundFile.Entry e : cf.entries()) byName.put(decodeName(e.name), e);
            loadStrings();
            loadCatalogue();
            loadSummary();
        } catch (IOException | RuntimeException e) {
            cf.close();
            throw e;
        }
    }

    /** A stream name as the package spells it: table streams start with "!", the rest are encoded in pairs. */
    static String decodeName(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            int c = raw.charAt(i);
            if (c == 0x4840) {
                out.append('!');
            } else if (c >= 0x3800 && c < 0x4800) {
                c -= 0x3800;
                out.append(CHARSET.charAt(c & 0x3F)).append(CHARSET.charAt((c >> 6) & 0x3F));
            } else if (c >= 0x4800 && c < 0x4840) {
                out.append(CHARSET.charAt(c - 0x4800));
            } else {
                out.append((char) c);
            }
        }
        return out.toString();
    }

    private byte[] stream(String name) throws IOException {
        CompoundFile.Entry e = byName.get(name);
        return e == null ? null : cf.read(e);
    }

    private void loadStrings() throws IOException {
        byte[] pool = stream("!_StringPool");
        byte[] data = stream("!_StringData");
        strings.add("");
        if (pool == null || data == null || pool.length < 4) return;
        int low = CompoundFile.le16(pool, 0), high = CompoundFile.le16(pool, 2);
        int codepage = low | ((high & 0x7FFF) << 16);
        if ((high & 0x8000) != 0) strRefBytes = 3;
        Charset cs = charsetFor(codepage);
        int count = pool.length / 4;
        int offset = 0;
        int i = 1;
        while (i < count) {
            int len = CompoundFile.le16(pool, i * 4);
            int refs = CompoundFile.le16(pool, i * 4 + 2);
            if (len == 0 && refs == 0) {
                strings.add("");
                i++;
                continue;
            }
            if (len == 0) {
                // A string over 64 KB: this entry carries the high word, the next one the low.
                if ((i + 1) * 4 + 4 > pool.length) break;
                len = (refs << 16) | CompoundFile.le16(pool, (i + 1) * 4);
                i += 2;
            } else {
                i += 1;
            }
            if (offset + len > data.length) len = Math.max(0, data.length - offset);
            strings.add(new String(data, offset, len, cs));
            offset += len;
        }
    }

    private static Charset charsetFor(int codepage) {
        try {
            if (codepage == 65001) return Charset.forName("UTF-8");
            if (codepage > 0) return Charset.forName("windows-" + codepage);
        } catch (Exception ignored) {
        }
        return Charset.forName("windows-1252");
    }

    private String str(int id) {
        return id > 0 && id < strings.size() ? strings.get(id) : "";
    }

    private int readRef(byte[] b, int at) {
        int v = CompoundFile.le16(b, at);
        if (strRefBytes == 3) v |= (b[at + 2] & 0xFF) << 16;
        return v;
    }

    private void loadCatalogue() throws IOException {
        byte[] tables = stream("!_Tables");
        if (tables != null) {
            for (int at = 0; at + strRefBytes <= tables.length; at += strRefBytes) tableNames.add(str(readRef(tables, at)));
        }
        byte[] cols = stream("!_Columns");
        if (cols == null) return;
        int rowSize = strRefBytes + 2 + strRefBytes + 2;
        int rows = cols.length / rowSize;
        Map<String, TreeMap<Integer, Column>> byTable = new HashMap<>();
        for (int r = 0; r < rows; r++) {
            String table = str(readRef(cols, r * strRefBytes));
            int number = CompoundFile.le16(cols, rows * strRefBytes + r * 2) ^ 0x8000;
            String name = str(readRef(cols, rows * (strRefBytes + 2) + r * strRefBytes));
            int type = CompoundFile.le16(cols, rows * (strRefBytes + 2 + strRefBytes) + r * 2) ^ 0x8000;
            byTable.computeIfAbsent(table, k -> new TreeMap<>()).put(number, new Column(name, type));
        }
        for (Map.Entry<String, TreeMap<Integer, Column>> e : byTable.entrySet()) {
            columns.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        }
    }

    public Set<String> tableNames() {
        return Collections.unmodifiableSet(tableNames);
    }

    public boolean hasTable(String name) {
        return tableNames.contains(name);
    }

    /** The table's rows as column name -> text; an absent table has none. */
    public List<Map<String, String>> table(String name) throws IOException {
        List<Map<String, String>> cached = cache.get(name);
        if (cached != null) return cached;
        List<Map<String, String>> rows = new ArrayList<>();
        List<Column> cols = columns.get(name);
        byte[] data = tableNames.contains(name) && cols != null ? stream("!" + name) : null;
        if (data != null && !cols.isEmpty()) {
            int rowSize = 0;
            for (Column c : cols) rowSize += c.width(strRefBytes);
            int count = rowSize == 0 ? 0 : data.length / rowSize;
            int[] base = new int[cols.size()];
            int acc = 0;
            for (int c = 0; c < cols.size(); c++) {
                base[c] = acc * count;
                acc += cols.get(c).width(strRefBytes);
            }
            for (int r = 0; r < count; r++) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < cols.size(); c++) {
                    Column col = cols.get(c);
                    int w = col.width(strRefBytes);
                    int at = base[c] + r * w;
                    String value;
                    if (col.isString()) {
                        value = str(w == 3 ? readRef(data, at) : CompoundFile.le16(data, at));
                    } else if ((col.type & TYPE_STRING) != 0) {
                        value = "";  // a binary column: its stream is not needed here
                    } else if (w == 2) {
                        int raw = CompoundFile.le16(data, at);
                        value = raw == 0 ? "" : Integer.toString((short) (raw ^ 0x8000));
                    } else {
                        int raw = CompoundFile.le32(data, at);
                        value = raw == 0 ? "" : Integer.toString(raw ^ 0x80000000);
                    }
                    row.put(col.name, value);
                }
                rows.add(row);
            }
        }
        cache.put(name, rows);
        return rows;
    }

    /** Copies the stream a Media row's "#name" cabinet names to [target]; false when there is none. */
    public boolean extractStream(String name, File target) throws IOException {
        CompoundFile.Entry e = byName.get(name);
        if (e == null) return false;
        cf.extract(e, target);
        return true;
    }

    // The summary information property set: title, subject, template ("x64;1033")...
    private void loadSummary() throws IOException {
        byte[] b = stream("\u0005SummaryInformation");
        if (b == null || b.length < 48) return;
        int section = CompoundFile.le32(b, 44);
        if (section < 0 || section + 8 > b.length) return;
        int count = CompoundFile.le32(b, section + 4);
        Map<Integer, Integer> offsets = new HashMap<>();
        for (int i = 0; i < count && section + 8 + i * 8 + 8 <= b.length; i++) {
            offsets.put(CompoundFile.le32(b, section + 8 + i * 8), CompoundFile.le32(b, section + 12 + i * 8));
        }
        Charset cs = Charset.forName("windows-1252");
        Integer cpAt = offsets.get(1);
        if (cpAt != null && section + cpAt + 6 <= b.length) cs = charsetFor(CompoundFile.le16(b, section + cpAt + 4));
        String[] names = {null, null, "title", "subject", "author", "keywords", "comments", "template", "lastauthor", "revision"};
        for (Map.Entry<Integer, Integer> e : offsets.entrySet()) {
            int pid = e.getKey();
            int at = section + e.getValue();
            if (pid <= 1 || pid >= names.length || names[pid] == null || at + 8 > b.length) continue;
            if (CompoundFile.le32(b, at) != 30) continue;  // VT_LPSTR
            int len = CompoundFile.le32(b, at + 4);
            if (len < 0 || at + 8 + len > b.length) continue;
            String text = new String(b, at + 8, len, cs);
            int nul = text.indexOf('\0');
            summary.put(names[pid], nul >= 0 ? text.substring(0, nul) : text);
        }
    }

    public String summary(String key) {
        String v = summary.get(key);
        return v == null ? "" : v;
    }

    @Override
    public void close() throws IOException {
        cf.close();
    }
}
