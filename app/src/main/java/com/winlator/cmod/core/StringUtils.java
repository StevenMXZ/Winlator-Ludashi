package com.winlator.cmod.core;

import android.content.Context;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Locale;

/* loaded from: classes10.dex */
public class StringUtils {
    public static String removeEndSlash(String value) {
        while (true) {
            if (value.endsWith("/") || value.endsWith("\\")) {
                value = value.substring(0, value.length() - 1);
            } else {
                return value;
            }
        }
    }

    public static String addEndSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    public static String insert(String text, int index, String value) {
        return text.substring(0, index) + value + text.substring(index);
    }

    public static String replace(String text, int start, int end, String value) {
        return text.substring(0, start) + value + text.substring(end);
    }

    public static String unescape(String path) {
        return path.replaceAll("\\\\([^\\\\]+)", "$1").replaceAll("\\\\([^\\\\]+)", "$1").replaceAll("\\\\\\\\", "\\\\").trim();
    }

    public static String parseIdentifier(Object text) {
        return text.toString().toLowerCase(Locale.ENGLISH).replaceAll(" *\\(([^\\)]+)\\)$", "").replaceAll("( \\+ )+| +", "-");
    }

    public static String parseNumber(Object text) {
        return text.toString().replaceAll("[^0-9\\.]+", "");
    }

    public static String getString(Context context, String resName) {
        try {
            int resID = context.getResources().getIdentifier(resName.toLowerCase(Locale.ENGLISH), "string", context.getPackageName());
            return context.getString(resID);
        } catch (Exception e) {
            return null;
        }
    }

    public static String formatBytes(long bytes) {
        return formatBytes(bytes, true);
    }

    public static String formatBytes(long bytes, boolean withSuffix) {
        if (bytes <= 0) {
            return "0 bytes";
        }
        String[] units = {"bytes", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024.0d));
        String suffix = withSuffix ? " " + units[digitGroups] : "";
        return String.format(Locale.ENGLISH, "%.2f", Double.valueOf(bytes / Math.pow(1024.0d, digitGroups))) + suffix;
    }

    public static String fromANSIString(byte[] bytes) {
        return fromANSIString(bytes, (Charset) null);
    }

    public static String fromANSIString(byte[] bytes, Charset charset) {
        String value = charset != null ? new String(bytes, charset) : new String(bytes);
        int indexOfNull = value.indexOf(0);
        return indexOfNull != -1 ? value.substring(0, indexOfNull) : value;
    }

    public static String fromANSIString(ByteBuffer data, int pos) {
        StringBuilder sb = new StringBuilder();
        for (int i = pos; data.get(i) != 0; i++) {
            sb.append((char) data.get(i));
        }
        return sb.toString();
    }

    public static String escapeDOSPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String escapedPath = path.replace("\\", "\\\\");
        return escapedPath.replace(" ", "\\ ");
    }

    public static String escapeFileDOSPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String escapedPath = path.replace("\\", "\\\\\\\\");
        return escapedPath.replace(" ", "\\\\");
    }
}
