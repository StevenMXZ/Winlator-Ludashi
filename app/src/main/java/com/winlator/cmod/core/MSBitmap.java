package com.winlator.cmod.core;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes10.dex */
public abstract class MSBitmap {
    public static Bitmap open(File targetFile) {
        byte[] bytes;
        if (!targetFile.isFile() || (bytes = FileUtils.read(targetFile)) == null) {
            return null;
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getShort() != 19778) {
            return null;
        }
        int fileSize = data.getInt();
        if (fileSize > targetFile.length()) {
            return null;
        }
        data.getInt();
        int dataOffset = data.getInt();
        int infoHeaderSize = data.getInt();
        int width = data.getInt();
        int height = data.getInt();
        short planes = data.getShort();
        short bitCount = data.getShort();
        int compression = data.getInt();
        int imageSize = data.getInt();
        data.getInt();
        data.getInt();
        data.getInt();
        data.getInt();
        if (width != 0 && height != 0) {
            boolean invertY = true;
            if (height < 0) {
                height *= -1;
                invertY = false;
            }
            ByteBuffer pixels = ByteBuffer.allocate(width * height * 4);
            byte r2 = 0;
            byte g2 = 0;
            byte b2 = 0;
            boolean started = false;
            boolean blank = true;
            int y = height - 1;
            int i = data.position();
            while (y >= 0) {
                int line = invertY ? y : (height - 1) - y;
                int fileSize2 = fileSize;
                int dataOffset2 = dataOffset;
                int infoHeaderSize2 = infoHeaderSize;
                short planes2 = planes;
                byte g22 = g2;
                byte b22 = b2;
                int infoHeaderSize3 = i;
                int x = 0;
                byte[] bytes2 = bytes;
                byte r22 = r2;
                while (x < width) {
                    int j = (line * width * 4) + (x * 4);
                    short bitCount2 = bitCount;
                    int i2 = infoHeaderSize3 + 1;
                    byte b1 = data.get(infoHeaderSize3);
                    int compression2 = compression;
                    int compression3 = i2 + 1;
                    byte g1 = data.get(i2);
                    int i3 = compression3 + 1;
                    byte r1 = data.get(compression3);
                    ByteBuffer data2 = data;
                    pixels.put(j + 2, b1);
                    pixels.put(j + 1, g1);
                    pixels.put(j + 0, r1);
                    int imageSize2 = imageSize;
                    pixels.put(j + 3, (byte) -1);
                    if (!started) {
                        b22 = b1;
                        g22 = g1;
                        r22 = r1;
                        started = true;
                    } else if (r1 != r22 || b1 != b22 || g1 != g22) {
                        blank = false;
                    }
                    x++;
                    infoHeaderSize3 = i3;
                    compression = compression2;
                    data = data2;
                    imageSize = imageSize2;
                    bitCount = bitCount2;
                }
                i = infoHeaderSize3 + (width % 4);
                y--;
                r2 = r22;
                g2 = g22;
                b2 = b22;
                bytes = bytes2;
                fileSize = fileSize2;
                dataOffset = dataOffset2;
                infoHeaderSize = infoHeaderSize2;
                planes = planes2;
                data = data;
            }
            if (blank) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(pixels);
            return bitmap;
        }
        return null;
    }

    public static boolean create(Bitmap bitmap, File outputFile) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int extraBytes = width % 4;
        int imageSize = height * ((width * 3) + extraBytes);
        int infoHeaderSize = 40;
        ByteBuffer buffer = ByteBuffer.allocate(54 + imageSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 19778);
        buffer.putInt(54 + imageSize);
        buffer.putInt(0);
        buffer.putInt(54);
        buffer.putInt(40);
        buffer.putInt(width);
        buffer.putInt(height);
        buffer.putShort((short) 1);
        buffer.putShort((short) 24);
        buffer.putInt(0);
        buffer.putInt(imageSize);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        int rowBytes = (width * 3) + extraBytes;
        int y = height - 1;
        int i = 0;
        while (y >= 0) {
            int x = 0;
            while (x < width) {
                int j = 54 + (y * rowBytes) + (x * 3);
                int i2 = i + 1;
                int pixel = pixels[i];
                int infoHeaderSize2 = infoHeaderSize;
                int infoHeaderSize3 = Color.blue(pixel);
                buffer.put(j + 0, (byte) infoHeaderSize3);
                buffer.put(j + 1, (byte) Color.green(pixel));
                buffer.put(j + 2, (byte) Color.red(pixel));
                x++;
                i = i2;
                imageSize = imageSize;
                infoHeaderSize = infoHeaderSize2;
            }
            int imageSize2 = imageSize;
            int infoHeaderSize4 = infoHeaderSize;
            if (extraBytes > 0) {
                int fillOffset = (y * rowBytes) + 54 + (width * 3);
                for (int j2 = fillOffset; j2 < fillOffset + extraBytes; j2++) {
                    buffer.put(j2, (byte) -1);
                }
            }
            y--;
            imageSize = imageSize2;
            infoHeaderSize = infoHeaderSize4;
        }
        try {
            FileOutputStream fos = new FileOutputStream(outputFile);
            try {
                fos.write(buffer.array());
                try {
                    fos.close();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            } catch (Throwable th) {
                try {
                    try {
                        fos.close();
                        throw th;
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                        throw th;
                    }
                } catch (IOException e2) {
                    return false;
                }
            }
        } catch (IOException e3) {
            return false;
        }
    }
}
