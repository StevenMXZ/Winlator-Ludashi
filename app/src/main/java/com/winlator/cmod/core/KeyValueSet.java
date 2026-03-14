package com.winlator.cmod.core;

import java.util.Iterator;

/* loaded from: classes10.dex */
public class KeyValueSet implements Iterable<String[]> {
    private String data;

    public KeyValueSet() {
        this.data = "";
        this.data = "";
    }

    public KeyValueSet(String data) {
        String str = "";
        this.data = "";
        if (data != null && !data.isEmpty()) {
            str = data;
        }
        this.data = str;
    }

    private int[] indexOfKey(String key) {
        int start = 0;
        int end = this.data.indexOf(",");
        if (end == -1) {
            end = this.data.length();
        }
        while (start < end) {
            int index = this.data.indexOf("=", start);
            String currKey = this.data.substring(start, index);
            if (currKey.equals(key)) {
                return new int[]{start, end};
            }
            start = end + 1;
            end = this.data.indexOf(",", start);
            if (end == -1) {
                end = this.data.length();
            }
        }
        return null;
    }

    public String get(String key) {
        Iterator<String[]> it = iterator();
        while (it.hasNext()) {
            String[] keyValue = it.next();
            if (keyValue[0].equals(key)) {
                return keyValue[1];
            }
        }
        return "";
    }

    public String get(String key, String fallback) {
        Iterator<String[]> it = iterator();
        while (it.hasNext()) {
            String[] keyValue = it.next();
            if (keyValue[0].equals(key)) {
                return keyValue[1];
            }
        }
        return fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key);
        return value.isEmpty() ? fallback : value.equals("1") || value.equals("t") || value.equalsIgnoreCase("true");
    }

    public float getFloat(String key, float fallback) {
        String value = get(key);
        try {
            if (!value.isEmpty()) {
                return Float.parseFloat(value);
            }
        } catch (NumberFormatException e) {
        }
        return fallback;
    }

    public void put(String key, Object value) {
        int[] range = indexOfKey(key);
        if (range != null) {
            this.data = StringUtils.replace(this.data, range[0], range[1], key + "=" + value);
        } else {
            this.data = (!this.data.isEmpty() ? this.data + "," : "") + key + "=" + value;
        }
    }

    public void put(String key, float value) {
        put(key, String.valueOf(value));
    }

    @Override // java.lang.Iterable
    public Iterator<String[]> iterator() {
        final int[] start = {0};
        final int[] end = {this.data.indexOf(",")};
        final String[] item = new String[2];
        return new Iterator<String[]>() { // from class: com.winlator.cmod.core.KeyValueSet.1
            @Override // java.util.Iterator
            public boolean hasNext() {
                return start[0] < end[0];
            }

            @Override // java.util.Iterator
            public String[] next() {
                int index = KeyValueSet.this.data.indexOf("=", start[0]);
                item[0] = KeyValueSet.this.data.substring(start[0], index);
                item[1] = KeyValueSet.this.data.substring(index + 1, end[0]);
                start[0] = end[0] + 1;
                end[0] = KeyValueSet.this.data.indexOf(",", start[0]);
                if (end[0] == -1) {
                    end[0] = KeyValueSet.this.data.length();
                }
                return item;
            }
        };
    }

    public String toString() {
        return this.data;
    }
}
