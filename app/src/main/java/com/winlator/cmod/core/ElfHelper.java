package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/* loaded from: classes10.dex */
public abstract class ElfHelper {
    private static final byte ELF_CLASS_32 = 1;
    private static final byte ELF_CLASS_64 = 2;

    private static int getEIClass(File binFile) {
        InputStream inStream;
        byte[] header;
        try {
            inStream = new FileInputStream(binFile);
            try {
                header = new byte[52];
                inStream.read(header);
            } finally {
            }
        } catch (IOException e) {
        }
        if (header[0] == Byte.MAX_VALUE && header[1] == 69 && header[2] == 76 && header[3] == 70) {
            byte b = header[4];
            inStream.close();
            return b;
        }
        inStream.close();
        return 0;
    }

    public static boolean is32Bit(File binFile) {
        return getEIClass(binFile) == 1;
    }

    public static boolean is64Bit(File binFile) {
        return getEIClass(binFile) == 2;
    }
}
