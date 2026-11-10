package com.winlator.cmod.components.offline;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class WineRegistryFile {
    private static final class Section {
        String header;
        final List<String> body = new ArrayList<>();
    }

    private final File path;
    private final List<String> preamble = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final Map<String, Section> index = new HashMap<>();
    private final Map<String, String> links = new HashMap<>();
    private boolean changed;
    public int written;

    public WineRegistryFile(File path) throws IOException {
        this.path = path;
        String text = new String(Files.readAllBytes(path.toPath()), StandardCharsets.ISO_8859_1);
        String root = "";
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < Math.min(4, lines.length); i++) {
            Matcher m = Pattern.compile("^;; All keys relative to (.*)$").matcher(lines[i]);
            if (m.matches()) root = strip(unescape(m.group(1))).toLowerCase(Locale.ROOT);
        }
        Section current = null;
        for (String line : lines) {
            if (line.startsWith("[")) {
                current = new Section();
                current.header = line;
                sections.add(current);
                String key = parseKey(line);
                if (key != null) index.putIfAbsent(key.toLowerCase(Locale.ROOT), current);
            } else if (current == null) {
                preamble.add(line);
            } else {
                current.body.add(line);
            }
        }
        for (Section s : sections) {
            if (!s.body.contains("#link")) continue;
            String key = parseKey(s.header);
            String target = linkTarget(s, root);
            if (key != null && target != null) links.put(key.toLowerCase(Locale.ROOT), target);
        }
    }

    private static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '\\') a++;
        while (b > a && s.charAt(b - 1) == '\\') b--;
        return s.substring(a, b);
    }

    /** Where a link key leads, relative to this file's root. */
    private static String linkTarget(Section s, String root) {
        for (int at = 0; at < s.body.size(); at++) {
            String line = s.body.get(at);
            if (!line.startsWith("\"SymbolicLinkValue\"=hex(6):")) continue;
            StringBuilder data = new StringBuilder(line.substring(line.indexOf(':') + 1));
            while (data.length() > 0 && data.charAt(data.length() - 1) == '\\' && at + 1 < s.body.size()) {
                data.setLength(data.length() - 1);
                data.append(s.body.get(++at).trim());
            }
            try {
                List<Byte> raw = new ArrayList<>();
                for (String part : data.toString().split(",")) if (!part.trim().isEmpty()) raw.add((byte) Integer.parseInt(part.trim(), 16));
                byte[] bytes = new byte[raw.size()];
                for (int i = 0; i < bytes.length; i++) bytes[i] = raw.get(i);
                String target = strip(new String(bytes, StandardCharsets.UTF_16LE));
                if (!root.isEmpty() && target.toLowerCase(Locale.ROOT).startsWith(root + "\\")) return target.substring(root.length() + 1);
            } catch (NumberFormatException e) {
                return null;
            }
            return null;
        }
        return null;
    }

    /** The key Wine really keeps [key] in: a path through a link key leads to the link's target. */
    String resolve(String key) {
        key = strip(key);
        for (int round = 0; round < 8; round++) {
            String lower = key.toLowerCase(Locale.ROOT);
            boolean moved = false;
            for (Map.Entry<String, String> l : links.entrySet()) {
                String link = l.getKey();
                if (lower.equals(link) || lower.startsWith(link + "\\")) {
                    key = l.getValue() + key.substring(link.length());
                    moved = true;
                    break;
                }
            }
            if (!moved) return key;
        }
        return key;
    }

    static String parseKey(String line) {
        int end = -1;
        for (int i = 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == ']') {
                end = i;
                break;
            }
        }
        return end < 0 ? null : unescape(line.substring(1, end));
    }

    // ------------------------------------------------------------- escaping, as Wine writes it

    static String escape(String text, String extra) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"' || extra.indexOf(c) >= 0) out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c == '\0') out.append("\\0");
            else if (c < ' ' || c > '~') out.append(String.format("\\x%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                i++;
                continue;
            }
            char next = text.charAt(i + 1);
            if (next == 'x') {
                int j = i + 2;
                while (j < text.length() && j < i + 6 && Character.digit(text.charAt(j), 16) >= 0) j++;
                if (j > i + 2) {
                    out.append((char) Integer.parseInt(text.substring(i + 2, j), 16));
                    i = j;
                    continue;
                }
            }
            switch (next) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case '0': out.append('\0'); break;
                default: out.append(next); break;
            }
            i += 2;
        }
        return out.toString();
    }

    static String keyLine(String key) {
        StringBuilder b = new StringBuilder("[");
        String[] parts = strip(key).split("\\\\");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append("\\\\");
            b.append(escape(parts[i], "[]"));
        }
        return b.append(']').toString();
    }

    /** name=data as one registry file line; null for a kind that has no line of its own. */
    static String valueLine(String name, String kind, Object data) {
        String head = name.isEmpty() ? "@" : "\"" + escape(name, "") + "\"";
        switch (kind) {
            case "sz": return head + "=\"" + escape(String.valueOf(data), "") + "\"";
            case "expand_sz": return head + "=str(2):\"" + escape(String.valueOf(data), "") + "\"";
            case "multi_sz": {
                StringBuilder all = new StringBuilder();
                if (data instanceof List) for (Object p : (List<?>) data) all.append(p).append('\0');
                else all.append(data).append('\0');
                return head + "=str(7):\"" + escape(all.toString(), "") + "\"";
            }
            case "dword": {
                long v = data instanceof Number ? ((Number) data).longValue() : Long.parseLong(String.valueOf(data).trim());
                return head + "=dword:" + String.format("%08x", v & 0xFFFFFFFFL);
            }
            case "binary": {
                String digits = String.valueOf(data).toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
                StringBuilder b = new StringBuilder(head).append("=hex:");
                for (int i = 0; i + 1 < digits.length(); i += 2) {
                    if (i > 0) b.append(',');
                    b.append(digits, i, i + 2);
                }
                return b.toString();
            }
            default: return null;
        }
    }

    private static final Pattern STRING_LINE = Pattern.compile("^(?:@|\"(?:[^\"\\\\]|\\\\.)*\")=(?:str\\(2\\):)?\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");
    private static final Pattern NAME = Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"=");

    /** The text of a string value line (plain or str(2)), or null for any other kind. */
    public static String stringOf(String line) {
        Matcher m = STRING_LINE.matcher(line);
        return m.matches() ? unescape(m.group(1)) : null;
    }

    private static String valueName(String line) {
        if (line.startsWith("@=")) return "";
        Matcher m = NAME.matcher(line);
        return m.find() ? unescape(m.group(1)).toLowerCase(Locale.ROOT) : null;
    }

    // ------------------------------------------------------------- values

    private Section section(String key) {
        return index.get(resolve(key).toLowerCase(Locale.ROOT));
    }

    /** {first, last + 1} of the value's lines in its section's body, or null. */
    private int[] find(Section s, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        int n = 0;
        while (n < s.body.size()) {
            int last = n;
            while (s.body.get(last).endsWith("\\") && last + 1 < s.body.size()) last++;
            if (wanted.equals(valueName(s.body.get(n)))) return new int[]{n, last + 1};
            n = last + 1;
        }
        return null;
    }

    /** Every value in the file: {its key as written, its name in lower case ("" for the default), its line(s)}. */
    public List<String[]> values() {
        List<String[]> out = new ArrayList<>();
        for (Section s : sections) {
            String key = parseKey(s.header);
            if (key == null) continue;
            int n = 0;
            while (n < s.body.size()) {
                int last = n;
                while (s.body.get(last).endsWith("\\") && last + 1 < s.body.size()) last++;
                String name = valueName(s.body.get(n));
                if (name != null) out.add(new String[]{key, name, String.join("\n", s.body.subList(n, last + 1))});
                n = last + 1;
            }
        }
        return out;
    }

    /** Every key in the file, as written. */
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        for (Section s : sections) {
            String key = parseKey(s.header);
            if (key != null) out.add(key);
        }
        return out;
    }

    /** Takes [key] out when it holds no values (nor a link) and has no keys under it; true if it did. */
    public boolean removeKeyIfEmpty(String key) {
        String lower = resolve(key).toLowerCase(Locale.ROOT);
        Section s = index.get(lower);
        if (s == null) return false;
        for (String line : s.body) {
            if (!line.isEmpty() && !line.startsWith("#time=") && !line.startsWith("#class=")) return false;
        }
        String below = lower + "\\";
        for (String k : index.keySet()) if (k.startsWith(below)) return false;
        sections.remove(s);
        index.remove(lower);
        changed = true;
        return true;
    }

    public String get(String key, String name) {
        Section s = section(key);
        if (s == null) return null;
        int[] f = find(s, name);
        return f == null ? null : String.join("\n", s.body.subList(f[0], f[1]));
    }

    public void ensureKey(String key) {
        key = resolve(key);
        String lower = key.toLowerCase(Locale.ROOT);
        if (index.containsKey(lower)) return;
        long now = System.currentTimeMillis();
        // Keep the file's last blank line after the new key, as Wine writes it.
        Section last = sections.isEmpty() ? null : sections.get(sections.size() - 1);
        List<String> tail = last != null ? last.body : preamble;
        while (!tail.isEmpty() && tail.get(tail.size() - 1).isEmpty()) tail.remove(tail.size() - 1);
        tail.add("");
        Section s = new Section();
        s.header = keyLine(key) + " " + (now / 1000);
        s.body.add(String.format("#time=%x", now * 10000 + 116444736000000000L));
        s.body.add("");
        sections.add(s);
        index.put(lower, s);
        changed = true;
    }

    /** Sets the value's line(s) to [raw], or removes the value when [raw] is null; true if that changed it. */
    public boolean put(String key, String name, String raw) {
        Section s = section(key);
        int[] f = s == null ? null : find(s, name);
        if (raw == null) {
            if (f == null) return false;
            s.body.subList(f[0], f[1]).clear();
            changed = true;
            return true;
        }
        List<String> lines = new ArrayList<>();
        for (String l : raw.split("\n", -1)) lines.add(l);
        if (f != null) {
            if (s.body.subList(f[0], f[1]).equals(lines)) return false;
            s.body.subList(f[0], f[1]).clear();
            s.body.addAll(f[0], lines);
        } else {
            ensureKey(key);
            s = section(key);
            int end = s.body.size();
            while (end > 0 && s.body.get(end - 1).isEmpty()) end--;
            s.body.addAll(end, lines);
        }
        changed = true;
        return true;
    }

    /** [current] (a string value line) with [item] added to its list, or null when it would be empty. */
    static String listLine(String name, String current, String item, String separator, boolean atEnd) {
        String text = current != null ? stringOf(current) : "";
        if (text == null) return current;  // not a string value: left as it is
        List<String> items = new ArrayList<>();
        for (String part : text.split(Pattern.quote(separator), -1)) {
            if (!part.isEmpty() && !part.equalsIgnoreCase(item)) items.add(part);
        }
        if (atEnd) items.add(item);
        else items.add(0, item);
        boolean expand = current == null || current.contains("=str(2):");
        return valueLine(name, expand ? "expand_sz" : "sz", String.join(separator, items));
    }

    /** Writes [v] (its hive's file is this one); counts it in [written] when it changed anything. */
    public void apply(RegValue v) {
        if (v.type.equals("key")) {
            ensureKey(v.key);
            return;
        }
        String current = get(v.key, v.name);
        String line;
        if (v.type.equals("append") || v.type.equals("prepend")) {
            line = listLine(v.name, current, String.valueOf(v.data), v.separator, v.type.equals("append"));
        } else {
            line = valueLine(v.name, v.type, v.data);
        }
        if (line == null) return;
        if (!line.equals(current) && put(v.key, v.name, line)) written++;
    }

    public void save() throws IOException {
        if (!changed) return;
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String l : preamble) {
            if (!first) out.append('\n');
            out.append(l);
            first = false;
        }
        for (Section s : sections) {
            if (!first) out.append('\n');
            out.append(s.header);
            first = false;
            for (String l : s.body) out.append('\n').append(l);
        }
        File temp = new File(path.getParentFile(), "." + path.getName() + ".ludashi");
        Files.write(temp.toPath(), out.toString().getBytes(StandardCharsets.ISO_8859_1));
        Files.move(temp.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        changed = false;
    }
}
