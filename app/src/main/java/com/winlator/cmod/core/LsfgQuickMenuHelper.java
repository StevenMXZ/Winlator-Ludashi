package com.winlator.cmod.core;

import com.winlator.cmod.container.Container;

import java.util.Locale;

public abstract class LsfgQuickMenuHelper {

    public static class Settings {
        public final int multiplier;
        public final float flowScale;
        public final boolean performanceMode;

        public Settings(int multiplier, float flowScale, boolean performanceMode) {
            this.multiplier = sanitizeMultiplier(multiplier);
            this.flowScale = sanitizeFlowScale(flowScale);
            this.performanceMode = performanceMode;
        }
    }

    public static boolean isAvailable(Container container) {
        return LsfgVkManager.isArmed(container);
    }

    public static Settings readSettings(Container container) {
        return new Settings(
                LsfgVkManager.multiplier(container),
                LsfgVkManager.flowScale(container),
                LsfgVkManager.performanceMode(container));
    }

    public static int sanitizeMultiplier(int multiplier) {
        if (multiplier < 2) return 0;
        return Math.max(2, Math.min(4, multiplier));
    }

    public static float sanitizeFlowScale(float flowScale) {
        return Math.max(0.25f, Math.min(1.0f, flowScale));
    }

    public static void applySettings(Container container, Settings settings) {
        int multiplier = sanitizeMultiplier(settings.multiplier);
        float flowScale = sanitizeFlowScale(settings.flowScale);
        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, String.valueOf(multiplier));
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale));
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, String.valueOf(settings.performanceMode));
        container.putExtra(LsfgVkManager.EXTRA_ENABLED, multiplier >= 2 ? "true" : "false");
        container.saveData();
        boolean enabled = multiplier >= 2;
        LsfgVkManager.updateConfigAtRuntime(container, enabled, enabled ? multiplier : 2, flowScale, settings.performanceMode);
    }
}
