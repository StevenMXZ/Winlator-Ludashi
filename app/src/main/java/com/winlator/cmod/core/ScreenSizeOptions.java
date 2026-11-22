package com.winlator.cmod.core;

import android.content.Context;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class ScreenSizeOptions {
    private static final int[] ADAPTIVE_HEIGHTS = {480, 540, 600, 720, 900, 1080, 1440};
    private static final String[] FIXED_16_9 = {
            "640x360", "854x480", "960x540", "1120x630", "1280x720", "1366x768", "1440x810", "1600x900", "1920x1080"
    };
    private static final String[] FIXED_4_3 = {
            "640x480", "800x600", "1024x768"
    };

    private ScreenSizeOptions() {}

    public static List<String> build(Context context) {
        int[] display = getDisplaySize(context);
        int nativeWidth = display[0];
        int nativeHeight = display[1];
        double ratio = (double) nativeWidth / nativeHeight;
        String ratioLabel = formatRatio(ratio);

        LinkedHashSet<Integer> heights = new LinkedHashSet<>();
        for (int height : ADAPTIVE_HEIGHTS) {
            if (height <= nativeHeight) heights.add(height);
        }
        heights.add(nativeHeight);

        List<Integer> sortedHeights = new ArrayList<>(heights);
        java.util.Collections.sort(sortedHeights);

        LinkedHashSet<String> resolutions = new LinkedHashSet<>();
        List<String> entries = new ArrayList<>();
        entries.add("Custom");

        for (int height : sortedHeights) {
            int width = height == nativeHeight ? nativeWidth : toEven(height * ratio);
            String resolution = width + "x" + height;
            if (resolutions.add(resolution)) entries.add(resolution + " (" + ratioLabel + ")");
        }

        for (String resolution : FIXED_16_9) {
            if (resolutions.add(resolution)) entries.add(resolution + " (16:9)");
        }
        for (String resolution : FIXED_4_3) {
            if (resolutions.add(resolution)) entries.add(resolution + " (4:3)");
        }
        return entries;
    }

    public static String defaultSize(Context context) {
        int[] display = getDisplaySize(context);
        int nativeWidth = display[0];
        int nativeHeight = display[1];
        if (nativeHeight <= 720) return nativeWidth + "x" + nativeHeight;
        int width = toEven(720.0 * nativeWidth / nativeHeight);
        return width + "x720";
    }

    public static String[] buildArray(Context context) {
        List<String> entries = build(context);
        return entries.toArray(new String[0]);
    }

    private static int[] getDisplaySize(Context context) {
        int width = 0;
        int height = 0;
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            Point point = new Point();
            display.getRealSize(point);
            width = point.x;
            height = point.y;
        } catch (Exception ignored) {
        }
        if (width <= 0 || height <= 0) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        if (shortSide <= 0) {
            longSide = 1920;
            shortSide = 1080;
        }
        return new int[]{toEven(longSide), toEven(shortSide)};
    }

    private static int toEven(double value) {
        return (int) (Math.round(value / 2.0) * 2);
    }

    private static String formatRatio(double ratio) {
        if (Math.abs(ratio - 16.0 / 10.0) < 0.01) return "16:10";
        if (Math.abs(ratio - 4.0 / 3.0) < 0.01) return "4:3";
        if (Math.abs(ratio - 3.0 / 2.0) < 0.01) return "3:2";
        double wide = ratio * 9.0;
        double rounded = Math.round(wide * 10.0) / 10.0;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05) {
            return Math.round(rounded) + ":9";
        }
        return String.format(Locale.US, "%.1f:9", rounded);
    }
}
