package com.winlator.cmod.xserver;

import android.graphics.Bitmap;

import android.hardware.HardwareBuffer;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.renderer.GPUImage;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Drawable extends XResource {
    public static final int HAL_PIXEL_FORMAT_RGBA_8888 = 1;
    public static final int HAL_PIXEL_FORMAT_BGRA_8888 = 5;
    
    public final short width;
    public short stride;
    public long backingAHB;
    public final short height;
    public final Visual visual;
    public int format = HAL_PIXEL_FORMAT_BGRA_8888;
    
    private GPUImage gpuImage = null;
    private boolean directScanout;
    private Runnable onDrawListener;
    private Callback<Drawable> onDestroyListener;
    private boolean offscreen = false;
    public final Object renderLock = new Object();

    static {
        System.loadLibrary("winlator");
        registerNatives();
    }

    public Drawable(int id, int width, int height, Visual visual, int format) {
        super(id);
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        this.format = format;
        this.backingAHB = allocate(width, height, format);
        if (this.backingAHB == 0) {
            throw new IllegalStateException("Drawable data initialized as null!");
        }
    }

    public static Drawable fromBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return null;

        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null,
                HAL_PIXEL_FORMAT_BGRA_8888);
        ByteBuffer dst = drawable.lockBuffer(drawable.backingAHB);
        if (dst == null) return null;

        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int dstStride = drawable.getStride();
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                int dstOffset = y * dstStride * 4;
                for (int x = 0; x < width; x++) {
                    int color = row[x];
                    int offset = dstOffset + x * 4;
                    dst.put(offset, (byte)(color & 0xff));
                    dst.put(offset + 1, (byte)((color >> 8) & 0xff));
                    dst.put(offset + 2, (byte)((color >> 16) & 0xff));
                    dst.put(offset + 3, (byte)((color >>> 24) & 0xff));
                }
            }
        } finally {
            drawable.unlockBuffer(drawable.backingAHB);
        }
        return drawable;
    }
    
    public Drawable(int id, int width, int height, Visual visual, GPUImage gpuImage) {
        super(id);
        if (width <= 0 || height <= 0 || gpuImage == null || gpuImage.hardwareBufferPtr == 0)
            throw new IllegalStateException("Imported Drawable has no valid hardware buffer");
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        this.gpuImage = gpuImage;
        this.backingAHB = gpuImage.hardwareBufferPtr;
        this.format = gpuImage.format;
    }

    public GPUImage getGPUImage() {
        return this.gpuImage;
    }

    public void setDirectScanout(boolean directScanout) {
        this.directScanout = directScanout;
    }

    public boolean isDirectScanout() {
        return directScanout;
    }

    public short getStride() {
        return gpuImage != null ? gpuImage.getStride() : stride;
    }

    public Runnable getOnDrawListener() {
        return onDrawListener;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public Callback<Drawable> getOnDestroyListener() {
        return onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    public void drawImage(short srcX, short srcY, short dstX, short dstY, short width, short height, byte depth, ByteBuffer data, short totalWidth, short totalHeight) {
        if (depth == 1) {
            drawBitmap(width, height, data, this.getStride(), backingAHB);
        }
        else if (depth == 24 || depth == 32) {
            dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
            dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
            if ((dstX + width) > this.width) width = (short)((this.width - dstX));
            if ((dstY + height) > this.height) height = (short)((this.height - dstY));

            copyArea1(srcX, srcY, dstX, dstY, width, height, totalWidth, this.getStride(), data, backingAHB);
        }

        data.rewind();

        if (onDrawListener != null) onDrawListener.run();
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);

        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        copyArea2(x, y, (short)0, (short)0, width, height, this.getStride(), width, backingAHB, dstData);

        dstData.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
        dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
        if ((dstX + width) > this.width) width = (short)(this.width - dstX);
        if ((dstY + height) > this.height) height = (short)(this.height - dstY);

        if (gcFunction == GraphicsContext.Function.COPY) {
            copyArea3(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.backingAHB, this.backingAHB);
        }
        else copyAreaOp(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.backingAHB, this.backingAHB, gcFunction.ordinal());
        if (onDrawListener != null) onDrawListener.run();
    }

    public void fillColor(int color) {
        fillRect(0, 0, width, height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)((this.width - x));
        if ((y + height) > this.height) height = (short)((this.height - y));

        fillRect((short)x, (short)y, (short)width, (short)height, color, this.getStride(), this.backingAHB);

        if (onDrawListener != null) onDrawListener.run();
    }

    public void drawLines(int color, int lineWidth, short... points) {
        for (int i = 2; i < points.length; i += 2) {
            drawLine(points[i-2], points[i-1], points[i+0], points[i+1], color, (short)lineWidth);
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        x0 = Mathf.clamp(x0, 0, width-lineWidth);
        y0 = Mathf.clamp(y0, 0, height-lineWidth);
        x1 = Mathf.clamp(x1, 0, width-lineWidth);
        y1 = Mathf.clamp(y1, 0, height-lineWidth);

        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), this.backingAHB);


        if (onDrawListener != null) onDrawListener.run();
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, srcDrawable.backingAHB, srcDrawable.getStride(), maskDrawable.backingAHB, maskDrawable.getStride(), this.width, this.height, this.getStride(), this.backingAHB);

        if (onDrawListener != null) onDrawListener.run();
    }
    
    public void updateDirect() {
        if (onDrawListener != null) onDrawListener.run();
    }
    
    public void setOffscreen(boolean offscreen) {
        this.offscreen = offscreen;
    }
    
    public boolean isOffscreen() {
        return this.offscreen;
    }

    @FastNative
    private static native void drawBitmap(short width, short height, ByteBuffer srcData, short stride, long dstAHB);

    @CriticalNative
    private static native void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, long srcAHB, short srcStride, long maskAHB, short maskStride, short width, short height, short stride, long dstAHB);

    @FastNative
    private static native void copyArea1(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, ByteBuffer srcData, long dstAHB);
    
    @FastNative
    private static native void copyArea2(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, long srcAHB, ByteBuffer dstData);
    
    @CriticalNative
    private static native void copyArea3(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, long srcAHB, long dstAHB);

    @CriticalNative
    private static native void copyAreaOp(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, long srcAHB, long dstAHB, int gcFunction);

    @CriticalNative
    private static native void fillRect(short x, short y, short width, short height, int color, short stride, long dstAHB);

    @CriticalNative
    private static native void drawLine(short x0, short y0, short x1, short y1, int color, short lineWidth, short stride, long dstAHB);
    
    @FastNative
    private native long allocate(int width, int height, int format);

    @FastNative
    public native ByteBuffer lockBuffer(long ahb);

    @FastNative
    public native void unlockBuffer(long ahb);
    private static native void registerNatives();
}
