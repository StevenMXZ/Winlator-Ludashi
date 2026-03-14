package com.winlator.cmod.xserver;

/* loaded from: classes11.dex */
public class PixmapFormat {
    public final byte bitsPerPixel;
    public final byte depth;
    public final byte scanlinePad;

    public PixmapFormat(int depth, int bitsPerPixel, int scanlinePad) {
        this.depth = (byte) depth;
        this.bitsPerPixel = (byte) bitsPerPixel;
        this.scanlinePad = (byte) scanlinePad;
    }
}
