package com.winlator.cmod.components.offline;

import java.util.List;

/**
 * One registry value a component writes into a prefix, in the shape the DroidDeck engine's
 * registry.json and the component recordings use: hive HKLM/HKCU, a key under it, a value name
 * ("" is the default value) and a kind: sz, expand_sz, multi_sz (data is a List of String), dword
 * (a Number), binary (hex digits), key (just make the key), append/prepend (add an item to a list
 * value such as PATH, split by [separator]).
 */
public final class RegValue {
    public final String hive, key, name, type, separator;
    public final Object data;

    public RegValue(String hive, String key, String name, String type, Object data, String separator) {
        this.hive = hive; this.key = key; this.name = name == null ? "" : name; this.type = type;
        this.data = data; this.separator = separator == null || separator.isEmpty() ? ";" : separator;
    }

    public RegValue(String hive, String key, String name, String type, Object data) {
        this(hive, key, name, type, data, ";");
    }

    @SuppressWarnings("unchecked")
    public List<String> parts() {
        return data instanceof List ? (List<String>) data : java.util.Collections.singletonList(String.valueOf(data));
    }
}
