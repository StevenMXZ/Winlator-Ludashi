package com.winlator.cmod.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class RuntimeBackendProbe {
    public enum FexMode { UNIXLIB, DLL, NA }

    private RuntimeBackendProbe() {}

    public static FexMode detect(File containerRoot) {
        File[] processes = new File("/proc").listFiles(file ->
                file.isDirectory() && isNumeric(file.getName()));
        if (processes == null) return FexMode.NA;

        String containerPath = containerRoot == null ? "" : containerRoot.getAbsolutePath();
        FexMode result = FexMode.NA;
        for (File process : processes) {
            File maps = new File(process, "maps");
            if (!maps.canRead()) continue;
            FexMode processMode = FexMode.NA;
            boolean belongsToContainer = false;
            try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    belongsToContainer |= !containerPath.isEmpty() && line.contains(containerPath);
                    if (line.contains("libarm64ecfex.so") || line.contains("libwow64fex.so")) {
                        processMode = FexMode.UNIXLIB;
                    } else if (processMode == FexMode.NA
                            && (line.contains("libarm64ecfex.dll") || line.contains("libwow64fex.dll"))) {
                        processMode = FexMode.DLL;
                    }
                }
            } catch (Exception ignored) {
                continue;
            }
            if (!belongsToContainer) continue;
            if (processMode == FexMode.UNIXLIB) return processMode;
            if (processMode == FexMode.DLL) result = processMode;
        }
        return result;
    }

    static FexMode inspectMaps(Iterable<String> lines) {
        FexMode mode = FexMode.NA;
        for (String line : lines) {
            if (line.contains("libarm64ecfex.so") || line.contains("libwow64fex.so")) {
                return FexMode.UNIXLIB;
            }
            if (line.contains("libarm64ecfex.dll") || line.contains("libwow64fex.dll")) {
                mode = FexMode.DLL;
            }
        }
        return mode;
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}

