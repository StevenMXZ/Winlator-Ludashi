package com.winlator.cmod.xserver;

import android.graphics.Bitmap;

import dalvik.annotation.optimization.FastNative;
import java.nio.ByteBuffer;

public class Pixmap extends XResource {
    public final Drawable drawable;

    public Pixmap(Drawable drawable) {
        super(drawable.id);
        this.drawable = drawable;
    }

    public Bitmap toBitmap(Pixmap maskPixmap) {
        long maskData = maskPixmap != null ? maskPixmap.drawable.getGPUImage().getAHB() : 0;
        short maskStride = maskPixmap != null ? maskPixmap.drawable.getStride() : 0;
        Bitmap bitmap = Bitmap.createBitmap(drawable.width, drawable.height, Bitmap.Config.ARGB_8888);
        toBitmap(drawable.getStride(), drawable.getGPUImage().getAHB(), maskStride, maskData, bitmap);
        return bitmap;
    }
    
    @FastNative
    private static native void toBitmap(short colorStride, long colorData, short maskStride, long maskData, Bitmap bitmap);
}
