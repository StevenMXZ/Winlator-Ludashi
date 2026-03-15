package com.winlator.cmod.box64;

/* loaded from: classes6.dex */
public class Box64Preset {
    public static final String COMPATIBILITY = "COMPATIBILITY";
    public static final String CUSTOM = "CUSTOM";
    public static final String INTERMEDIATE = "INTERMEDIATE";
    public static final String PERFORMANCE = "PERFORMANCE";
    public static final String STABILITY = "STABILITY";
    public final String id;
    public final String name;

    public Box64Preset(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean isCustom() {
        return this.id.startsWith("CUSTOM");
    }

    public String toString() {
        return this.name;
    }
}
