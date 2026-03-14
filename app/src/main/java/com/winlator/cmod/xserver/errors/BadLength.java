package com.winlator.cmod.xserver.errors;

/* loaded from: classes13.dex */
public class BadLength extends XRequestError {
    public static final int ERROR_CODE = 16;

    public BadLength() {
        super(16, 0);
    }

    public BadLength(int data) {
        super(16, data);
    }

    public BadLength(String message) {
        super(16, 0);
        System.err.println("BadLength: " + message);
    }
}
