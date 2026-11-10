package com.winlator.cmod.components.offline;

import java.util.List;


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
