package com.winlator.cmod.core;

/** One registry policy for startup and live Exclusive Input changes. */
public final class JoystickRegistry {
    public static final String KEY = "Software\\Wine\\DirectInput\\Joysticks";

    private JoystickRegistry() {}

    public static String value(boolean dinput, boolean exclusive) {
        return exclusive ? (dinput ? "override" : "disabled") : null;
    }

    public static String[] names() {
        String[] names = new String[8];
        for (int i = 0; i < 4; i++) {
            names[i * 2] = "Generic HID Gamepad " + i;
            names[i * 2 + 1] = "ric HID Gamepad " + i;
        }
        return names;
    }

    public static String regFile(boolean dinput, boolean exclusive) {
        StringBuilder out = new StringBuilder("REGEDIT4\r\n\r\n[HKEY_CURRENT_USER\\" + KEY + "]\r\n");
        String value = value(dinput, exclusive);
        for (String name : names()) {
            out.append('"').append(name).append("\"=");
            out.append(value == null ? "-" : "\"" + value + "\"").append("\r\n");
        }
        return out.append("\r\n").toString();
    }
}
