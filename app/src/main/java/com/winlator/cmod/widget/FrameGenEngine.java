package com.winlator.cmod.widget;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;

/** Frame generation engines a game can pick. Only one runs at a time. */
public final class FrameGenEngine {
    public static final String KEY = "frameGenEngine";
    public static final String TUNING = "tuning";
    public static final String LSFG = "lsfg";
    public static final String WFG = "wfg";

    public static final String[] IDS = {TUNING, LSFG, WFG};
    public static final String[] LABELS = {"Tuning", "LSFG", "WFG"};

    private FrameGenEngine() {}

    public static String normalize(String engine) {
        if (LSFG.equals(engine) || WFG.equals(engine)) return engine;
        return TUNING;
    }

    public static int indexOf(String engine) {
        String id = normalize(engine);
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    public static String idAt(int index) {
        return index >= 0 && index < IDS.length ? IDS[index] : TUNING;
    }

    /** A shortcut overrides its container. */
    public static String of(Container container, Shortcut shortcut) {
        String containerEngine = container != null ? container.getExtra(KEY, TUNING) : TUNING;
        String engine = shortcut != null ? shortcut.getExtra(KEY, containerEngine) : containerEngine;
        return normalize(engine);
    }
}
