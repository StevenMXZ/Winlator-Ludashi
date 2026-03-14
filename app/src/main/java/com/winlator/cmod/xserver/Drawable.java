package com.winlator.cmod.xserver;

import android.graphics.Bitmap;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.xserver.GraphicsContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes11.dex */
public class Drawable extends XResource {
    private ByteBuffer data;
    private boolean directScanout;
    public final short height;
    private Callback<Drawable> onDestroyListener;
    private Runnable onDrawListener;
    public final Object renderLock;
    private Texture texture;
    public final Visual visual;
    public final short width;

    private static native void copyArea(short s, short s2, short s3, short s4, short s5, short s6, short s7, short s8, ByteBuffer byteBuffer, ByteBuffer byteBuffer2);

    private static native void copyAreaOp(short s, short s2, short s3, short s4, short s5, short s6, short s7, short s8, ByteBuffer byteBuffer, ByteBuffer byteBuffer2, int i);

    private static native void drawAlphaMaskedBitmap(byte b, byte b2, byte b3, byte b4, byte b5, byte b6, ByteBuffer byteBuffer, ByteBuffer byteBuffer2, ByteBuffer byteBuffer3);

    private static native void drawBitmap(short s, short s2, ByteBuffer byteBuffer, ByteBuffer byteBuffer2);

    private static native void drawLine(short s, short s2, short s3, short s4, int i, short s5, short s6, ByteBuffer byteBuffer);

    private static native void fillRect(short s, short s2, short s3, short s4, int i, short s5, ByteBuffer byteBuffer);

    private static native void fromBitmap(Bitmap bitmap, ByteBuffer byteBuffer);

    static {
        System.loadLibrary("winlator");
    }

    public Drawable(int id, int width, int height, Visual visual) {
        super(id);
        this.texture = new Texture();
        this.renderLock = new Object();
        this.directScanout = false;
        this.width = (short) width;
        this.height = (short) height;
        this.visual = visual;
        this.data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        if (this.data == null) {
            throw new IllegalStateException("Drawable.data initialized as null!");
        }
    }

    public static Drawable fromBitmap(Bitmap bitmap) {
        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null);
        fromBitmap(bitmap, drawable.data);
        return drawable;
    }

    public Texture getTexture() {
        return this.texture;
    }

    public void setTexture(Texture texture) {
        if (texture instanceof GPUImage) {
            this.data = ((GPUImage) texture).getVirtualData();
        }
        this.texture = texture;
    }

    public ByteBuffer getData() {
        return this.data;
    }

    public void setData(ByteBuffer data) {
        if (data == null) {
            throw new IllegalArgumentException("Attempting to set Drawable.data to null!");
        }
        this.data = data;
    }

    public void setDirectScanout(boolean value) {
        this.directScanout = value;
    }

    public boolean isDirectScanout() {
        return this.directScanout;
    }

    private short getStride() {
        return this.texture instanceof GPUImage ? ((GPUImage) this.texture).getStride() : this.width;
    }

    public Runnable getOnDrawListener() {
        return this.onDrawListener;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public Callback<Drawable> getOnDestroyListener() {
        return this.onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    /* JADX WARN: Removed duplicated region for block: B:10:? A[RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0078  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void drawImage(short r19, short r20, short r21, short r22, short r23, short r24, byte r25, java.nio.ByteBuffer r26, short r27, short r28) {
        /*
            r18 = this;
            r0 = r18
            r1 = r23
            r2 = r24
            r3 = r25
            r4 = 1
            if (r3 != r4) goto L13
            java.nio.ByteBuffer r5 = r0.data
            r15 = r26
            drawBitmap(r1, r2, r15, r5)
            goto L1e
        L13:
            r15 = r26
            r5 = 24
            if (r3 == r5) goto L23
            r5 = 32
            if (r3 != r5) goto L1e
            goto L23
        L1e:
            r5 = r21
            r17 = r22
            goto L67
        L23:
            short r5 = r0.width
            int r5 = r5 - r4
            r6 = 0
            r7 = r21
            int r5 = com.winlator.cmod.math.Mathf.clamp(r7, r6, r5)
            short r5 = (short) r5
            short r7 = r0.height
            int r7 = r7 - r4
            r8 = r22
            int r6 = com.winlator.cmod.math.Mathf.clamp(r8, r6, r7)
            short r14 = (short) r6
            int r6 = r5 + r1
            short r7 = r0.width
            if (r6 <= r7) goto L42
            short r6 = r0.width
            int r6 = r6 - r5
            short r1 = (short) r6
        L42:
            int r6 = r14 + r2
            short r7 = r0.height
            if (r6 <= r7) goto L4c
            short r6 = r0.height
            int r6 = r6 - r14
            short r2 = (short) r6
        L4c:
            short r13 = r18.getStride()
            java.nio.ByteBuffer r12 = r0.data
            r6 = r19
            r7 = r20
            r8 = r5
            r9 = r14
            r10 = r1
            r11 = r2
            r16 = r12
            r12 = r27
            r17 = r14
            r14 = r26
            r15 = r16
            copyArea(r6, r7, r8, r9, r10, r11, r12, r13, r14, r15)
        L67:
            java.nio.ByteBuffer r6 = r0.data
            r6.rewind()
            r26.rewind()
            com.winlator.cmod.renderer.Texture r6 = r0.texture
            r6.setNeedsUpdate(r4)
            java.lang.Runnable r4 = r0.onDrawListener
            if (r4 == 0) goto L7d
            java.lang.Runnable r4 = r0.onDrawListener
            r4.run()
        L7d:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.xserver.Drawable.drawImage(short, short, short, short, short, short, byte, java.nio.ByteBuffer, short, short):void");
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        short x2 = (short) Mathf.clamp((int) x, 0, this.width - 1);
        short y2 = (short) Mathf.clamp((int) y, 0, this.height - 1);
        short width2 = x2 + width > this.width ? (short) (this.width - x2) : width;
        copyArea(x2, y2, (short) 0, (short) 0, width2, y2 + height > this.height ? (short) (this.height - y2) : height, getStride(), width2, this.data, dstData);
        this.data.rewind();
        dstData.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        short dstX2 = (short) Mathf.clamp((int) dstX, 0, this.width - 1);
        short dstY2 = (short) Mathf.clamp((int) dstY, 0, this.height - 1);
        short width2 = dstX2 + width > this.width ? (short) (this.width - dstX2) : width;
        short height2 = dstY2 + height > this.height ? (short) (this.height - dstY2) : height;
        if (gcFunction == GraphicsContext.Function.COPY) {
            copyArea(srcX, srcY, dstX2, dstY2, width2, height2, drawable.getStride(), getStride(), drawable.data, this.data);
        } else {
            copyAreaOp(srcX, srcY, dstX2, dstY2, width2, height2, drawable.getStride(), getStride(), drawable.data, this.data, gcFunction.ordinal());
        }
        this.data.rewind();
        drawable.data.rewind();
        this.texture.setNeedsUpdate(true);
        if (this.onDrawListener != null) {
            this.onDrawListener.run();
        }
    }

    public void fillColor(int color) {
        fillRect(0, 0, this.width, this.height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        int x2 = (short) Mathf.clamp(x, 0, this.width - 1);
        int y2 = (short) Mathf.clamp(y, 0, this.height - 1);
        if (x2 + width > this.width) {
            width = (short) (this.width - x2);
        }
        if (y2 + height > this.height) {
            height = (short) (this.height - y2);
        }
        fillRect((short) x2, (short) y2, (short) width, (short) height, color, getStride(), this.data);
        this.data.rewind();
        this.texture.setNeedsUpdate(true);
        if (this.onDrawListener != null) {
            this.onDrawListener.run();
        }
    }

    public void drawLines(int color, int lineWidth, short... points) {
        for (int i = 2; i < points.length; i += 2) {
            drawLine(points[i - 2], points[i - 1], points[i + 0], points[i + 1], color, (short) lineWidth);
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        drawLine((short) Mathf.clamp(x0, 0, this.width - lineWidth), (short) Mathf.clamp(y0, 0, this.height - lineWidth), (short) Mathf.clamp(x1, 0, this.width - lineWidth), (short) Mathf.clamp(y1, 0, this.height - lineWidth), color, (short) lineWidth, getStride(), this.data);
        this.data.rewind();
        this.texture.setNeedsUpdate(true);
        if (this.onDrawListener != null) {
            this.onDrawListener.run();
        }
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, srcDrawable.data, maskDrawable.data, this.data);
        this.data.rewind();
        this.texture.setNeedsUpdate(true);
        if (this.onDrawListener != null) {
            this.onDrawListener.run();
        }
    }
}
