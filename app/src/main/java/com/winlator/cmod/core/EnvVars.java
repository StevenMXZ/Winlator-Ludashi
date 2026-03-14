package com.winlator.cmod.core;

import java.util.Iterator;
import java.util.LinkedHashMap;

/* loaded from: classes10.dex */
public class EnvVars implements Iterable<String> {
    private final LinkedHashMap<String, String> data = new LinkedHashMap<>();

    public EnvVars() {
    }

    public EnvVars(String values) {
        putAll(values);
    }

    public void put(String name, Object value) {
        this.data.put(name, String.valueOf(value));
    }

    public void putAll(String values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String[] parts = values.split(" ");
        for (String part : parts) {
            int index = part.indexOf("=");
            String name = part.substring(0, index);
            String value = part.substring(index + 1);
            this.data.put(name, value);
        }
    }

    public void putAll(EnvVars envVars) {
        this.data.putAll(envVars.data);
    }

    public String get(String name) {
        return this.data.getOrDefault(name, "");
    }

    public void remove(String name) {
        this.data.remove(name);
    }

    public boolean has(String name) {
        return this.data.containsKey(name);
    }

    public void clear() {
        this.data.clear();
    }

    public boolean isEmpty() {
        return this.data.isEmpty();
    }

    public String toString() {
        return String.join(" ", toStringArray());
    }

    public String toEscapedString() {
        String result = "";
        for (String key : this.data.keySet()) {
            if (!result.isEmpty()) {
                result = result + " ";
            }
            String value = this.data.get(key);
            result = result + key + "=" + value.replace(" ", "\\ ");
        }
        return result;
    }

    public String[] toStringArray() {
        String[] stringArray = new String[this.data.size()];
        int index = 0;
        for (String key : this.data.keySet()) {
            stringArray[index] = key + "=" + this.data.get(key);
            index++;
        }
        return stringArray;
    }

    @Override // java.lang.Iterable
    public Iterator<String> iterator() {
        return this.data.keySet().iterator();
    }
}
