package com.winlator.cmod.core;

public final class ShortcutNames {
    private ShortcutNames() {}
    public static String resolve(String filename, String desktopName, boolean managed, String override) {
        if (!managed) return filename;
        String preferred = override == null ? "" : override.trim();
        if (preferred.isEmpty()) preferred = desktopName == null ? "" : desktopName.trim();
        return preferred.isEmpty() ? filename : preferred.replace('\n', ' ').replace('\r', ' ');
    }
}
