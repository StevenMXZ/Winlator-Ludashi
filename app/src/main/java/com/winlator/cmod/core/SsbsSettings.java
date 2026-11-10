package com.winlator.cmod.core;

/** Wine's native ARM64 signal fix; no preload library is involved. */
public final class SsbsSettings {
    private SsbsSettings() {}

    public static boolean resolve(boolean containerDefault, String gameOverride) {
        if ("1".equals(gameOverride)) return true;
        if ("0".equals(gameOverride)) return false;
        return containerDefault;
    }
}
