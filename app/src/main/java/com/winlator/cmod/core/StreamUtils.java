package com.winlator.cmod.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/* loaded from: classes10.dex */
public class StreamUtils {
    public static final int BUFFER_SIZE = 65536;

    public static byte[] copyToByteArray(InputStream inStream) {
        if (inStream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream outStream = new ByteArrayOutputStream(65536);
        copy(inStream, outStream);
        return outStream.toByteArray();
    }

    public static boolean copy(InputStream inStream, OutputStream outStream) {
        try {
            byte[] buffer = new byte[65536];
            while (true) {
                int amountRead = inStream.read(buffer);
                if (amountRead != -1) {
                    outStream.write(buffer, 0, amountRead);
                } else {
                    outStream.flush();
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
    }
}
