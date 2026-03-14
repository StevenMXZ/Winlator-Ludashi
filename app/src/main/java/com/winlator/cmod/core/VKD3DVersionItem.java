package com.winlator.cmod.core;

/* loaded from: classes10.dex */
public class VKD3DVersionItem {
    private final String displayName;
    private final String identifier;

    public VKD3DVersionItem(String verName) {
        this.identifier = verName;
        this.displayName = this.identifier;
    }

    public VKD3DVersionItem(String verName, int verCode) {
        this.identifier = verName + "-" + verCode;
        this.displayName = this.identifier;
    }

    public String getIdentifier() {
        return this.identifier;
    }

    public String toString() {
        return this.displayName;
    }
}
