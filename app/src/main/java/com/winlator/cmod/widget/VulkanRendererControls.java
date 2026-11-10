package com.winlator.cmod.widget;

public interface VulkanRendererControls {
    void setFilterMode(int mode);
    void setPostFXMode(int mode);
    void setStretchMode(int mode);
    void setSharpness(float sharpness);
    void setFpsLimit(int fps);
    void setFrameGenerationConfig(boolean enabled, String cachePath, int multiplier,
                                  int targetRate, int flowScalePercent, float refreshRate);
    boolean setFrameGenerationEnabled(boolean enabled);
    void setFrameGenerationSettings(int multiplier, int targetRate, int flowScalePercent);
    int getFrameGenerationMultiplier();
    int getFrameGenerationTargetRate();
    int getFrameGenerationFlowScale();
    boolean isFrameGenerationEnabled();
    boolean isFrameGenerationRequested();
    long getGeneratedFrameCount();
    void setOnFrameGenerationStateChangedListener(Runnable listener);
}
