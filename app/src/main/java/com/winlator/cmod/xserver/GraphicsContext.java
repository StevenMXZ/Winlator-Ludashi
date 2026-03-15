package com.winlator.cmod.xserver;

import androidx.core.view.ViewCompat;

/* loaded from: classes11.dex */
public class GraphicsContext extends XResource {
    public static final int FLAG_ARC_MODE = 4194304;
    public static final int FLAG_BACKGROUND = 8;
    public static final int FLAG_CAP_STYLE = 64;
    public static final int FLAG_CLIP_MASK = 524288;
    public static final int FLAG_CLIP_X_ORIGIN = 131072;
    public static final int FLAG_CLIP_Y_ORIGIN = 262144;
    public static final int FLAG_DASHES = 2097152;
    public static final int FLAG_DASH_OFFSET = 1048576;
    public static final int FLAG_FILL_RULE = 512;
    public static final int FLAG_FILL_STYLE = 256;
    public static final int FLAG_FONT = 16384;
    public static final int FLAG_FOREGROUND = 4;
    public static final int FLAG_FUNCTION = 1;
    public static final int FLAG_GRAPHICS_EXPOSURES = 65536;
    public static final int FLAG_JOIN_STYLE = 128;
    public static final int FLAG_LINE_STYLE = 32;
    public static final int FLAG_LINE_WIDTH = 16;
    public static final int FLAG_PLANE_MASK = 2;
    public static final int FLAG_STIPPLE = 2048;
    public static final int FLAG_SUBWINDOW_MODE = 32768;
    public static final int FLAG_TILE = 1024;
    public static final int FLAG_TILE_STIPPLE_X_ORIGIN = 4096;
    public static final int FLAG_TILE_STIPPLE_Y_ORIGIN = 8192;
    private int background;
    public final Drawable drawable;
    private int foreground;
    private Function function;
    private int lineWidth;
    private int planeMask;
    private SubwindowMode subwindowMode;

    public enum Function {
        CLEAR,
        AND,
        AND_REVERSE,
        COPY,
        AND_INVERTED,
        NO_OP,
        XOR,
        OR,
        NOR,
        EQUIV,
        INVERT,
        OR_REVERSE,
        COPY_INVERTED,
        OR_INVERTED,
        NAND,
        SET
    }

    public enum SubwindowMode {
        CLIP_BY_CHILDREN,
        INCLUDE_INFERIORS
    }

    public GraphicsContext(int id, Drawable drawable) {
        super(id);
        this.function = Function.COPY;
        this.background = ViewCompat.MEASURED_SIZE_MASK;
        this.foreground = 0;
        this.lineWidth = 1;
        this.planeMask = -1;
        this.subwindowMode = SubwindowMode.CLIP_BY_CHILDREN;
        this.drawable = drawable;
    }

    public int getForeground() {
        return this.foreground;
    }

    public void setForeground(int foreground) {
        this.foreground = foreground;
    }

    public int getBackground() {
        return this.background;
    }

    public void setBackground(int background) {
        this.background = background;
    }

    public int getLineWidth() {
        return this.lineWidth;
    }

    public void setLineWidth(int lineWidth) {
        this.lineWidth = lineWidth;
    }

    public int getPlaneMask() {
        return this.planeMask;
    }

    public void setPlaneMask(int planeMask) {
        this.planeMask = planeMask;
    }

    public Function getFunction() {
        return this.function;
    }

    public void setFunction(Function function) {
        this.function = function;
    }

    public SubwindowMode getSubwindowMode() {
        return this.subwindowMode;
    }

    public void setSubwindowMode(SubwindowMode subwindowMode) {
        this.subwindowMode = subwindowMode;
    }
}
