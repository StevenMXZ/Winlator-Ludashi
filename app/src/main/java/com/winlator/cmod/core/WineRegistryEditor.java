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

    private Location createKey(String str) {
        this.lastParentKeyPosition = 0;
        Location parentKeyLocation = getParentKeyLocation(str);
        boolean success = false;
        int offset = 0;
        int start = 0;
        char[] buffer = new char[65536];
        File tempFile = FileUtils.createTempFile(this.file.getParentFile(), FileUtils.getBasename(this.file.getPath()));
        BufferedReader bufferedReader = null;
        BufferedWriter bufferedWriter = null;
        
        try {
            bufferedReader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            bufferedWriter = new BufferedWriter(new FileWriter(tempFile), 65536);
            
            int length;
            if (parentKeyLocation != null) {
                length = parentKeyLocation.end + 1;
            } else {
                length = (int) this.cloneFile.length();
            }
            
            int i = 0;
            while (i < length) {
                int min = Math.min(buffer.length, length - i);
                bufferedReader.read(buffer, 0, min);
                bufferedWriter.write(buffer, 0, min);
                start += min;
                i += min;
            }
            offset = start;
            
            long currentTimeMillis = System.currentTimeMillis() + 116444736000000000L;
            String str2 = "\n[" + escape(str) + "] " + ((currentTimeMillis - 116444736000000000L) / 1000) + 
                          String.format(Locale.ENGLISH, "\n#time=%x%08x", 
                                        Long.valueOf(currentTimeMillis >> 32), 
                                        Integer.valueOf((int) currentTimeMillis)) + "\n";
            bufferedWriter.write(str2);
            start += str2.length() - 1;
            
            while (true) {
                int read = bufferedReader.read(buffer);
                if (read == -1) {
                    break;
                }
                bufferedWriter.write(buffer, 0, read);
            }
            
            success = true;
            
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (bufferedWriter != null) {
                    bufferedWriter.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        if (success) {
            this.modified = true;
            tempFile.renameTo(this.cloneFile);
            return new Location(offset, start, start);
        } else {
            tempFile.delete();
            return null;
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
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            reader.skip(valueLocation.start);
            success = reader.read(buffer) == buffer.length;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        if (success) {
            return unescape(new String(buffer));
        }
        return null;
    }

    private void setRawValue(String key, String name, String value) {
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
        BufferedReader reader = null;
        BufferedWriter writer = null;
        
        try {
            reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            writer = new BufferedWriter(new FileWriter(tempFile), 65536);
            
            int end = valueLocation != null ? valueLocation.start : keyLocation.end;
            int i = 0;
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
                }
                writer.write(buffer, 0, length2);
            }
            
            success = true;
            
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        if (success) {
            this.modified = true;
            tempFile.renameTo(this.cloneFile);
        } else {
            tempFile.delete();
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
        BufferedReader reader = null;
        BufferedWriter writer = null;
        
        try {
            reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            writer = new BufferedWriter(new FileWriter(tempFile), 65536);
            
            int length = 0;
            int i = 0;
            while (i < location.offset) {
                length = Math.min(buffer.length, location.offset - i);
                reader.read(buffer, 0, length);
                writer.write(buffer, 0, length);
                i += length;
            }
            
            boolean skipLine = length > 1 && buffer[length - 1] == '\n';
            reader.skip((location.end - location.offset) + (skipLine ? 1 : 0));
            
            while (true) {
                int length2 = reader.read(buffer);
                if (length2 == -1) {
                    break;
                }
                writer.write(buffer, 0, length2);
            }
            
            success = true;
            
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
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
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            
            int lastIndex = key.lastIndexOf("\\");
            String parentKey = (this.lastParentKeyPosition != 0 || lastIndex == -1) ? null : "[" + escape(key.substring(0, lastIndex));
            
            if (this.lastParentKeyPosition > 0) {
                reader.skip(this.lastParentKeyPosition);
            }
            
            String key2 = "[" + escape(key) + (!keyAsPrefix ? "]" : "");
            
            int totalLength = this.lastParentKeyPosition;
            int start = -1;
            int end = -1;
            int emptyLines = 0;
            int offset = 0;
            
            String line;
            while ((line = reader.readLine()) != null) {
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
            return location;
            
        } catch (IOException e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
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

    private Location getValueLocation(Location keyLocation, String name) {
        if (keyLocation.start == keyLocation.end) {
            return null;
        }
        
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            reader.skip(keyLocation.start);
            
            String searchStr;
            if (name != null) {
                searchStr = "\"" + escape(name) + "\"=";
            } else {
                searchStr = "@=";
            }
            
            int currentPos = 0;
            int valueStart = -1;
            int valueEnd = -1;
            int nameStart = -1;
            
            String line;
            while ((line = reader.readLine()) != null && currentPos < keyLocation.length()) {
                if (valueStart == -1) {
                    if (line.startsWith(searchStr)) {
                        nameStart = currentPos - 1;
                        valueStart = currentPos + searchStr.length();
                    }
                } else {
                    if (line.isEmpty() || !lineHasName(line)) {
                        valueEnd = currentPos - 1;
                        break;
                    } else {
                        break;
                    }
                }
                currentPos += line.length() + 1;
            }
            
            if (valueEnd == -1) {
                valueEnd = currentPos - 1;
            }
            
            if (valueStart != -1) {
                return new Location(keyLocation.start + nameStart, 
                                   keyLocation.start + valueStart, 
                                   keyLocation.start + valueEnd);
            }
            return null;
            
        } catch (IOException e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void importReg(String regFile) {
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
                    
                    if ("String".equals(type)) {
                        setStringValue(key, name, value);
                    } else if ("Dword".equals(type)) {
                        setDwordValue(key, name, Integer.parseInt(value));
                    }
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
