package com.winlator.cmod.core;

/* loaded from: classes10.dex */
public abstract class DefaultVersion {
    public static final String BOX64 = "0.4.1";
    public static final String D8VK = "1.0";
    public static final String DXVK;
    public static final String FEXCORE = "2601";
    public static final String VKD3D = "None";
    public static final String WOWBOX64 = "0.4.1";
    public static final String WRAPPER = "System";
    public static final String WRAPPER_ADRENO = "turnip26.0.0";

    static {
        DXVK = GPUInformation.getRenderer(null, null).contains("Mali") ? "1.10.3" : "2.3.1";
    }
}
