package com.winlator.cmod.renderer;

/* loaded from: classes12.dex */
public class NativeRenderer {
    public static native void eglSwapBuffersWrapper(long j, long j2);

    public static native long getEGLDisplay();

    public static native long getEGLSurface();

    public static native boolean initEGLContext(Object obj);

    static {
        System.loadLibrary("winlator");
    }
}
