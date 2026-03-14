package com.winlator.cmod.core;

import android.content.Context;
import androidx.core.os.EnvironmentCompat;

/* loaded from: classes10.dex */
public abstract class GPUInformation {
    public static native String[] enumerateExtensions(String str, Context context);

    public static native String getRenderer(String str, Context context);

    public static native int getVendorID(String str, Context context);

    public static native String getVulkanVersion(String str, Context context);

    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !driverName.equals(DefaultVersion.WRAPPER)) {
            return false;
        }
        String renderer = getRenderer(driverName, context);
        return !renderer.toLowerCase().contains(EnvironmentCompat.MEDIA_UNKNOWN);
    }

    static {
        System.loadLibrary("winlator");
    }
}
