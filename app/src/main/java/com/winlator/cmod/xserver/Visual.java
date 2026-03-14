package com.winlator.cmod.xserver;

/* loaded from: classes11.dex */
public class Visual {
    public final byte bitsPerRGBValue;
    public final int blueMask;
    public final byte depth;
    public final boolean displayable;
    public final int greenMask;
    public final int id;
    public final int redMask;
    public final byte visualClass = 4;
    public final short colormapEntries = 256;

    public Visual(int id, boolean displayable, int depth, int bitsPerRGBValue, int redMask, int greenMask, int blueMask) {
        this.id = id;
        this.displayable = displayable;
        this.depth = (byte) depth;
        this.bitsPerRGBValue = (byte) bitsPerRGBValue;
        this.redMask = redMask;
        this.greenMask = greenMask;
        this.blueMask = blueMask;
    }
}
