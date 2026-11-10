package com.winlator.cmod.core;

import android.content.Context;

public abstract class GPUInformation {

    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !driverName.equals("System"))
            return false;

        String renderer = getRenderer(driverName, context);

        return !renderer.toLowerCase().contains("unknown");
    }
    public static synchronized native String getVulkanVersion(String driverName, Context context);
    public static synchronized native int getVendorID(String driverName, Context context);
    public static synchronized native String getRenderer(String driverName, Context context);
    public static synchronized native String[] enumerateExtensions(String driverName, Context context);
    /** Offscreen real 3D cube. Runs only in the separate GPU test service process. */
    public static synchronized native String runGpuCubeBenchmark(String driverName, Context context, Object previewReporter);

    /** Offscreen transfer smoke/throughput test. Use only in the separate GPU test process. */
    public static synchronized native String runGpuTransferTest(String driverName, Context context, boolean preflight);

    /** Read-only Vulkan capabilities (tab-separated rows). */
    public static synchronized native String[] queryCapabilities(String driverName, Context context);


    static {
        System.loadLibrary("winlator");
    }
}
