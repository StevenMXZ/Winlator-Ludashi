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
    public static final int HAL_PIXEL_FORMAT_BGRA_8888 = 5;
    
    public final short width;
    public final short height;
    public final Visual visual;
    
    private GPUImage gpuImage;
    private Runnable onDrawListener;
    private Callback<Drawable> onDestroyListener;
    private boolean offscreen = false;
    public final Object renderLock = new Object();

    static {
        System.loadLibrary("winlator");
    }

    public Drawable(int id, int width, int height, Visual visual, int format) {
        super(id);
        
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        
        gpuImage = new GPUImage(this.width, this.height, format);
        if (gpuImage.getAHB() == 0) {
            throw new IllegalStateException("Drawable data initialized as null!");
        }
    }
    
    public Drawable(int id, int width, int height, Visual visual, GPUImage gpuImage) {
        super(id);
        
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        
        if (gpuImage.getAHB() == 0) {
            throw new IllegalStateException("Drawable data initialized as null!");
        }
        
        this.gpuImage = gpuImage;
    }
    
    public GPUImage getGPUImage() {
        return this.gpuImage;
    }

    public short getStride() {
        return gpuImage.getStride();
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
            drawBitmap(width, height, data, this.getStride(), gpuImage.getAHB());
        }
        else if (depth == 24 || depth == 32) {
            dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
            dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
            if ((dstX + width) > this.width) width = (short)((this.width - dstX));
            if ((dstY + height) > this.height) height = (short)((this.height - dstY));

            copyArea1(srcX, srcY, dstX, dstY, width, height, totalWidth, this.getStride(), data, gpuImage.getAHB());
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

        copyArea2(x, y, (short)0, (short)0, width, height, this.getStride(), width, gpuImage.getAHB(), dstData);

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
            copyArea3(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.getGPUImage().getAHB(), this.gpuImage.getAHB());
        }
        else copyAreaOp(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.getGPUImage().getAHB(), this.gpuImage.getAHB(), gcFunction.ordinal());
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

        fillRect((short)x, (short)y, (short)width, (short)height, color, this.getStride(), this.gpuImage.getAHB());

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

        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), this.gpuImage.getAHB());


        if (onDrawListener != null) onDrawListener.run();
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, srcDrawable.getGPUImage().getAHB(), srcDrawable.getStride(), maskDrawable.getGPUImage().getAHB(), maskDrawable.getStride(), this.width, this.height, this.getStride(), this.gpuImage.getAHB());

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
}
    