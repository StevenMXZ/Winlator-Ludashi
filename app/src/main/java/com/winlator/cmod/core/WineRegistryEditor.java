package com.winlator.cmod.core;

import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.math.Mathf;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes10.dex */
public class WineRegistryEditor implements Closeable {
    private final File cloneFile;
    private final File file;
    private boolean modified = false;
    private boolean createKeyIfNotExist = true;
    private int lastParentKeyPosition = 0;
    private String lastParentKey = "";

    public static class Location {
        public final int end;
        public final int offset;
        public final int start;

        public Location(int offset, int start, int end) {
            this.offset = offset;
            this.start = start;
            this.end = end;
        }

        public int length() {
            return this.end - this.start;
        }
    }

    public WineRegistryEditor(File file) {
        this.file = file;
        this.cloneFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));
        if (!file.isFile()) {
            try {
                this.cloneFile.createNewFile();
            } catch (IOException e) {
            }
        } else {
            FileUtils.copy(file, this.cloneFile);
        }
    }

    private static String escape(String str) {
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String str) {
        return str.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static boolean lineHasName(String line) {
        int index;
        int index2 = line.indexOf(34);
        return (index2 == -1 || (index = line.indexOf(34, index2)) == -1 || line.indexOf(61, index) == -1) ? false : true;
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public void close() {
        if (this.modified && this.cloneFile.exists()) {
            this.cloneFile.renameTo(this.file);
        } else {
            this.cloneFile.delete();
        }
    }

    private void resetLastParentKeyPositionIfNeed(String newKey) {
        int lastIndex = newKey.lastIndexOf("\\");
        if (lastIndex == -1) {
            this.lastParentKeyPosition = 0;
            this.lastParentKey = "";
        } else {
            String parentKey = newKey.substring(0, lastIndex);
            if (!parentKey.equals(this.lastParentKey)) {
                this.lastParentKeyPosition = 0;
            }
            this.lastParentKey = parentKey;
        }
    }

    public void setCreateKeyIfNotExist(boolean createKeyIfNotExist) {
        this.createKeyIfNotExist = createKeyIfNotExist;
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r10v0, types: [java.io.BufferedReader] */
    /* JADX WARN: Type inference failed for: r2v17, types: [java.io.BufferedReader] */
    /* JADX WARN: Type inference failed for: r2v19 */
    /* JADX WARN: Type inference failed for: r2v2 */
    /* JADX WARN: Type inference failed for: r2v3, types: [java.io.BufferedReader] */
    private Location createKey(String str) {
        ?? bufferedReader;
        ?? r2;
        Throwable th;
        BufferedWriter bufferedWriter;
        int i;
        int length;
        Throwable th2;
        ?? r22;
        this.lastParentKeyPosition = 0;
        Location parentKeyLocation = getParentKeyLocation(str);
        boolean z = false;
        int i2 = 0;
        int i3 = 0;
        char[] cArr = new char[65536];
        File createTempFile = FileUtils.createTempFile(this.file.getParentFile(), FileUtils.getBasename(this.file.getPath()));
        try {
            try {
                bufferedReader = new BufferedReader(new FileReader(this.cloneFile), 65536);
                try {
                    bufferedWriter = new BufferedWriter(new FileWriter(createTempFile), 65536);
                    i = 0;
                } catch (Throwable th3) {
                    r2 = bufferedReader;
                    th = th3;
                }
            } catch (IOException e) {
            }
        } catch (IOException e2) {
        }
        try {
            if (parentKeyLocation != null) {
                try {
                    length = parentKeyLocation.end + 1;
                } catch (Throwable th4) {
                    th2 = th4;
                    parentKeyLocation = bufferedReader;
                    try {
                        bufferedWriter.close();
                        throw th2;
                    } catch (Throwable th5) {
                        th2.addSuppressed(th5);
                        throw th2;
                    }
                }
            } else {
                try {
                    length = (int) this.cloneFile.length();
                } catch (Throwable th6) {
                    parentKeyLocation = bufferedReader;
                    th2 = th6;
                    bufferedWriter.close();
                    throw th2;
                }
            }
            while (i < length) {
                int min = Math.min(cArr.length, length - i);
                bufferedReader.read(cArr, 0, min);
                bufferedWriter.write(cArr, 0, min);
                i3 += min;
                i += min;
            }
            i2 = i3;
            long currentTimeMillis = System.currentTimeMillis() + 116444736000000000L;
            Location location = bufferedReader;
            try {
                try {
                    String str2 = "\n[" + escape(str) + "] " + ((currentTimeMillis - 116444736000000000L) / 1000) + String.format(Locale.ENGLISH, "\n#time=%x%08x", Long.valueOf(currentTimeMillis >> 32), Integer.valueOf((int) currentTimeMillis)) + "\n";
                    bufferedWriter.write(str2);
                    i3 += str2.length() - 1;
                    while (true) {
                        r22 = location;
                        try {
                            int read = r22.read(cArr);
                            if (read == -1) {
                                break;
                            }
                            bufferedWriter.write(cArr, 0, read);
                            location = r22;
                        } catch (Throwable th7) {
                            th2 = th7;
                            parentKeyLocation = r22;
                            bufferedWriter.close();
                            throw th2;
                        }
                    }
                    z = true;
                    bufferedWriter.close();
                    r22.close();
                    if (z) {
                        this.modified = true;
                        createTempFile.renameTo(this.cloneFile);
                        return new Location(i2, i3, i3);
                    }
                    createTempFile.delete();
                    return null;
                } catch (Throwable th8) {
                    parentKeyLocation = location;
                    th2 = th8;
                }
            } catch (Throwable th9) {
                parentKeyLocation = location;
                th2 = th9;
            }
        } catch (Throwable th10) {
            th = th10;
            r2 = parentKeyLocation;
            try {
                r2.close();
                throw th;
            } catch (Throwable th11) {
                th.addSuppressed(th11);
                throw th;
            }
        }
    }

    public String getStringValue(String key, String name) {
        return getStringValue(key, name, null);
    }

    public String getStringValue(String key, String name, String fallback) {
        String value = getRawValue(key, name);
        return value != null ? value.substring(1, value.length() - 1) : fallback;
    }

    public void setStringValue(String key, String name, String value) {
        setRawValue(key, name, value != null ? "\"" + escape(value) + "\"" : "\"\"");
    }

    public Integer getDwordValue(String key, String name) {
        return getDwordValue(key, name, null);
    }

    public Integer getDwordValue(String key, String name, Integer fallback) {
        String value = getRawValue(key, name);
        return value != null ? Integer.decode("0x" + value.substring(6)) : fallback;
    }

    public void setDwordValue(String key, String name, int value) {
        setRawValue(key, name, "dword:" + String.format("%08x", Integer.valueOf(value)));
    }

    public void setHexValue(String key, String name, String value) {
        int start = ((int) Mathf.roundTo(name.length(), 2.0f)) + 7;
        StringBuilder lines = new StringBuilder();
        int j = start;
        for (int i = 0; i < value.length(); i++) {
            if (i > 0 && i % 2 == 0) {
                lines.append(",");
            }
            int j2 = j + 1;
            if (j <= 56) {
                j = j2;
            } else {
                lines.append("\\\n  ");
                j = 8;
            }
            lines.append(value.charAt(i));
        }
        setRawValue(key, name, "hex:" + ((Object) lines));
    }

    public void setHexValue(String key, String name, byte[] bytes) {
        StringBuilder data = new StringBuilder();
        for (byte b : bytes) {
            data.append(String.format(Locale.ENGLISH, "%02x", Integer.valueOf(Byte.toUnsignedInt(b))));
        }
        setHexValue(key, name, data.toString());
    }

    private String getRawValue(String key, String name) {
        Location valueLocation;
        this.lastParentKeyPosition = 0;
        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null || (valueLocation = getValueLocation(keyLocation, name)) == null) {
            return null;
        }
        boolean success = false;
        char[] buffer = new char[valueLocation.length()];
        try {
            BufferedReader reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            try {
                reader.skip(valueLocation.start);
                success = reader.read(buffer) == buffer.length;
                reader.close();
            } finally {
            }
        } catch (IOException e) {
        }
        if (success) {
            return unescape(new String(buffer));
        }
        return null;
    }

    private void setRawValue(String key, String name, String value) {
        BufferedReader reader;
        BufferedWriter writer;
        int i;
        resetLastParentKeyPositionIfNeed(key);
        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null) {
            if (this.createKeyIfNotExist) {
                keyLocation = createKey(key);
            } else {
                return;
            }
        }
        Location valueLocation = getValueLocation(keyLocation, name);
        char[] buffer = new char[65536];
        boolean success = false;
        File tempFile = FileUtils.createTempFile(this.file.getParentFile(), FileUtils.getBasename(this.file.getPath()));
        try {
            reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            try {
                writer = new BufferedWriter(new FileWriter(tempFile), 65536);
                i = 0;
            } finally {
            }
        } catch (IOException e) {
        }
        try {
            int end = valueLocation != null ? valueLocation.start : keyLocation.end;
            while (i < end) {
                int length = Math.min(buffer.length, end - i);
                reader.read(buffer, 0, length);
                writer.write(buffer, 0, length);
                i += length;
            }
            if (valueLocation == null) {
                writer.write("\n" + (name != null ? "\"" + escape(name) + "\"" : "@") + "=" + value);
            } else {
                writer.write(value);
                reader.skip(valueLocation.length());
            }
            while (true) {
                int length2 = reader.read(buffer);
                if (length2 == -1) {
                    break;
                } else {
                    writer.write(buffer, 0, length2);
                }
            }
            success = true;
            writer.close();
            reader.close();
            if (success) {
                this.modified = true;
                tempFile.renameTo(this.cloneFile);
            } else {
                tempFile.delete();
            }
        } finally {
        }
    }

    public void removeValue(String key, String name) {
        Location valueLocation;
        this.lastParentKeyPosition = 0;
        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null || (valueLocation = getValueLocation(keyLocation, name)) == null) {
            return;
        }
        removeRegion(valueLocation);
    }

    public boolean removeKey(String key) {
        return removeKey(key, false);
    }

    public boolean removeKey(String key, boolean removeTree) {
        this.lastParentKeyPosition = 0;
        boolean removed = false;
        if (!removeTree) {
            Location location = getKeyLocation(key, false);
            return location != null && removeRegion(location);
        }
        while (true) {
            Location location2 = getKeyLocation(key, true);
            if (location2 != null) {
                if (removeRegion(location2)) {
                    removed = true;
                }
            } else {
                return removed;
            }
        }
    }

    private boolean removeRegion(Location location) {
        char[] buffer = new char[65536];
        boolean success = false;
        File tempFile = FileUtils.createTempFile(this.file.getParentFile(), FileUtils.getBasename(this.file.getPath()));
        try {
            BufferedReader reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), 65536);
                int length = 0;
                int i = 0;
                while (i < location.offset) {
                    try {
                        length = Math.min(buffer.length, location.offset - i);
                        reader.read(buffer, 0, length);
                        writer.write(buffer, 0, length);
                        i += length;
                    } finally {
                    }
                }
                boolean skipLine = length > 1 && buffer[length + (-1)] == '\n';
                reader.skip((location.end - location.offset) + (skipLine ? 1 : 0));
                while (true) {
                    int length2 = reader.read(buffer);
                    if (length2 == -1) {
                        break;
                    }
                    writer.write(buffer, 0, length2);
                }
                success = true;
                writer.close();
                reader.close();
            } finally {
            }
        } catch (IOException e) {
        }
        if (success) {
            this.modified = true;
            tempFile.renameTo(this.cloneFile);
        } else {
            tempFile.delete();
        }
        return success;
    }

    private Location getKeyLocation(String key) {
        return getKeyLocation(key, false);
    }

    private Location getKeyLocation(String key, boolean keyAsPrefix) {
        Throwable th;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            try {
                try {
                    int lastIndex = key.lastIndexOf("\\");
                    String parentKey = (this.lastParentKeyPosition != 0 || lastIndex == -1) ? null : "[" + escape(key.substring(0, lastIndex));
                    if (this.lastParentKeyPosition > 0) {
                        reader.skip(this.lastParentKeyPosition);
                    }
                    String key2 = "[" + escape(key) + (!keyAsPrefix ? "]" : "");
                    try {
                        int totalLength = this.lastParentKeyPosition;
                        int start = -1;
                        int end = -1;
                        int emptyLines = 0;
                        int offset = 0;
                        while (true) {
                            String line = reader.readLine();
                            if (line == null) {
                                break;
                            }
                            if (start == -1) {
                                if (parentKey != null && line.startsWith(parentKey)) {
                                    this.lastParentKeyPosition = totalLength;
                                    parentKey = null;
                                }
                                if (parentKey == null && line.startsWith(key2)) {
                                    offset = totalLength - 1;
                                    start = line.length() + totalLength + 1;
                                }
                            } else {
                                if (line.startsWith("[")) {
                                    end = Math.max(-1, (totalLength - emptyLines) - 1);
                                    break;
                                }
                                emptyLines = line.isEmpty() ? emptyLines + 1 : 0;
                            }
                            totalLength += line.length() + 1;
                        }
                        if (end == -1) {
                            end = totalLength - 1;
                        }
                        Location location = start != -1 ? new Location(offset, start, end) : null;
                        reader.close();
                        return location;
                    } catch (Throwable th2) {
                        th = th2;
                        try {
                            reader.close();
                            throw th;
                        } catch (Throwable th3) {
                            th.addSuppressed(th3);
                            throw th;
                        }
                    }
                } catch (IOException e) {
                    return null;
                }
            } catch (Throwable th4) {
                th = th4;
            }
        } catch (IOException e2) {
        }
    }

    private Location getParentKeyLocation(String key) {
        String[] parts = key.split("\\\\");
        ArrayList<String> stack = new ArrayList<>(Arrays.asList(parts).subList(0, parts.length - 1));
        while (!stack.isEmpty()) {
            String currentKey = String.join("\\", stack);
            Location location = getKeyLocation(currentKey, true);
            if (location != null) {
                return location;
            }
            stack.remove(stack.size() - 1);
        }
        return null;
    }

    /* JADX WARN: Code restructure failed: missing block: B:29:0x0077, code lost:
    
        r4 = r1 - 1;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private com.winlator.cmod.core.WineRegistryEditor.Location getValueLocation(com.winlator.cmod.core.WineRegistryEditor.Location r12, java.lang.String r13) {
        /*
            r11 = this;
            int r0 = r12.start
            int r1 = r12.end
            r2 = 0
            if (r0 != r1) goto L8
            return r2
        L8:
            java.io.BufferedReader r0 = new java.io.BufferedReader     // Catch: java.io.IOException -> L9e
            java.io.FileReader r1 = new java.io.FileReader     // Catch: java.io.IOException -> L9e
            java.io.File r3 = r11.cloneFile     // Catch: java.io.IOException -> L9e
            r1.<init>(r3)     // Catch: java.io.IOException -> L9e
            r3 = 65536(0x10000, float:9.18355E-41)
            r0.<init>(r1, r3)     // Catch: java.io.IOException -> L9e
            int r1 = r12.start     // Catch: java.lang.Throwable -> L94
            long r3 = (long) r1     // Catch: java.lang.Throwable -> L94
            r0.skip(r3)     // Catch: java.lang.Throwable -> L94
            if (r13 == 0) goto L3c
            java.lang.StringBuilder r1 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L94
            r1.<init>()     // Catch: java.lang.Throwable -> L94
            java.lang.String r3 = "\""
            java.lang.StringBuilder r1 = r1.append(r3)     // Catch: java.lang.Throwable -> L94
            java.lang.String r3 = escape(r13)     // Catch: java.lang.Throwable -> L94
            java.lang.StringBuilder r1 = r1.append(r3)     // Catch: java.lang.Throwable -> L94
            java.lang.String r3 = "\"="
            java.lang.StringBuilder r1 = r1.append(r3)     // Catch: java.lang.Throwable -> L94
            java.lang.String r1 = r1.toString()     // Catch: java.lang.Throwable -> L94
            goto L3e
        L3c:
            java.lang.String r1 = "@="
        L3e:
            r13 = r1
            r1 = 0
            r3 = -1
            r4 = -1
            r5 = 0
        L43:
            java.lang.String r6 = r0.readLine()     // Catch: java.lang.Throwable -> L94
            r7 = r6
            r8 = -1
            if (r6 == 0) goto L7a
            int r6 = r12.length()     // Catch: java.lang.Throwable -> L94
            if (r1 >= r6) goto L7a
            if (r3 != r8) goto L62
            boolean r6 = r7.startsWith(r13)     // Catch: java.lang.Throwable -> L94
            if (r6 == 0) goto L6f
            int r5 = r1 + (-1)
            int r6 = r13.length()     // Catch: java.lang.Throwable -> L94
            int r6 = r6 + r1
            r3 = r6
            goto L6f
        L62:
            boolean r6 = r7.isEmpty()     // Catch: java.lang.Throwable -> L94
            if (r6 != 0) goto L77
            boolean r6 = lineHasName(r7)     // Catch: java.lang.Throwable -> L94
            if (r6 == 0) goto L6f
            goto L77
        L6f:
            int r6 = r7.length()     // Catch: java.lang.Throwable -> L94
            int r6 = r6 + 1
            int r1 = r1 + r6
            goto L43
        L77:
            int r4 = r1 + (-1)
        L7a:
            if (r4 != r8) goto L7e
            int r4 = r1 + (-1)
        L7e:
            if (r3 == r8) goto L8f
            com.winlator.cmod.core.WineRegistryEditor$Location r6 = new com.winlator.cmod.core.WineRegistryEditor$Location     // Catch: java.lang.Throwable -> L94
            int r8 = r12.start     // Catch: java.lang.Throwable -> L94
            int r8 = r8 + r5
            int r9 = r12.start     // Catch: java.lang.Throwable -> L94
            int r9 = r9 + r3
            int r10 = r12.start     // Catch: java.lang.Throwable -> L94
            int r10 = r10 + r4
            r6.<init>(r8, r9, r10)     // Catch: java.lang.Throwable -> L94
            goto L90
        L8f:
            r6 = r2
        L90:
            r0.close()     // Catch: java.io.IOException -> L9e
            return r6
        L94:
            r1 = move-exception
            r0.close()     // Catch: java.lang.Throwable -> L99
            goto L9d
        L99:
            r3 = move-exception
            r1.addSuppressed(r3)     // Catch: java.io.IOException -> L9e
        L9d:
            throw r1     // Catch: java.io.IOException -> L9e
        L9e:
            r0 = move-exception
            return r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.core.WineRegistryEditor.getValueLocation(com.winlator.cmod.core.WineRegistryEditor$Location, java.lang.String):com.winlator.cmod.core.WineRegistryEditor$Location");
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public void importReg(String regFile) {
        char c;
        try {
            JSONObject jobj = new JSONObject(regFile);
            Iterator<String> iterator = jobj.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                JSONArray entries = jobj.getJSONArray(key);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    String type = entry.getString(ContentProfile.MARK_TYPE);
                    String name = entry.getString("name").isEmpty() ? null : entry.getString("name");
                    String value = entry.getString("value");
                    switch (type.hashCode()) {
                        case -1808118735:
                            if (type.equals("String")) {
                                c = 0;
                                break;
                            }
                            c = 65535;
                            break;
                        case 66454862:
                            if (type.equals("Dword")) {
                                c = 1;
                                break;
                            }
                            c = 65535;
                            break;
                        default:
                            c = 65535;
                            break;
                    }
                    switch (c) {
                        case 0:
                            setStringValue(key, name, value);
                            break;
                        case 1:
                            setDwordValue(key, name, Integer.parseInt(value));
                            break;
                    }
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
