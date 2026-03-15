package com.winlator.cmod.fexcore;

/* loaded from: classes3.dex */
public class FEXCorePreset {
    public static final String COMPATIBILITY = "COMPATIBILITY";
    public static final String CUSTOM = "CUSTOM";
    public static final String INTERMEDIATE = "INTERMEDIATE";
    public static final String PERFORMANCE = "PERFORMANCE";
    public static final String STABILITY = "STABILITY";
    public final String id;
    public final String name;

    public FEXCorePreset(String id, String name) {
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
