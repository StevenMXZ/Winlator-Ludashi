package com.winlator.cmod.contents;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric version ordering for display only; catalog/recommendation order is unchanged. */
public final class ContentVersionOrder {
    private static final Pattern FEX = Pattern.compile("\\d{6}|\\d{4}(?:\\.\\d+)?");
    private static final Pattern COMPACT_BOX = Pattern.compile("^0?(\\d{3})(?:\\D|$)");
    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+){0,3}");
    private static final Pattern REVISION = Pattern.compile("^-([0-9]+)(?:\\D|$)");

    private ContentVersionOrder() {}

    public static int compare(String leftType, String left, String rightType, String right) {
        int[] a = parts(leftType, left), b = parts(rightType, right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int order = Integer.compare(i < a.length ? a[i] : 0, i < b.length ? b[i] : 0);
            if (order != 0) return order;
        }
        return 0;
    }

    private static int number(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int[] parts(String type, String name) {
        String clean = (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replace("arm64ec", "").replace("x86_64", "");
        if ("FEXCore".equals(type)) {
            Matcher matcher = FEX.matcher(clean);
            if (!matcher.find()) return new int[0];
            String[] token = matcher.group().split("\\.");
            String base = token[0];
            return new int[] { number(base.substring(0, 2)), number(base.substring(2, 4)),
                    base.length() == 6 ? number(base.substring(4)) : token.length > 1 ? number(token[1]) : 0 };
        }
        if ("Box64".equals(type) || "WOWBox64".equals(type)) {
            Matcher matcher = COMPACT_BOX.matcher(clean);
            if (matcher.find()) {
                String digits = matcher.group(1);
                return new int[] { number(digits.substring(0, 1)), number(digits.substring(1, 2)),
                        number(digits.substring(2, 3)) };
            }
        }
        if ("DXVK".equals(type) && clean.startsWith("11.1"))
            clean = "1.1.1" + clean.substring(4);
        Matcher matcher = VERSION.matcher(clean);
        if (!matcher.find()) return new int[0];
        String[] fields = matcher.group().split("\\.");
        int[] values = new int[5];
        for (int i = 0; i < fields.length; i++) values[i] = number(fields[i]);
        if ("Proton".equals(type) || "Wine".equals(type)) {
            Matcher revision = REVISION.matcher(clean.substring(matcher.end()));
            if (revision.find()) values[4] = number(revision.group(1));
        }
        return values;
    }
}
