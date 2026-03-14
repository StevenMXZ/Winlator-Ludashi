package com.winlator.cmod.xserver.errors;

/* loaded from: classes13.dex */
public class BadSHMSegment extends XRequestError {
    public static final int ERROR_CODE = 128;

    public BadSHMSegment(int id) {
        super(128, id);
    }

    public BadSHMSegment(String message) {
        super(128, 0);
        System.err.println("BadSHMSegment: " + message);
    }
}
