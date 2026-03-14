package com.winlator.cmod.core;

import java.io.File;

/* loaded from: classes10.dex */
public class PatchElf {
    private long elfInstancePtr = 0;
    private File elfFile = null;

    private native boolean addNeeded(long j, String str);

    private native boolean addRPath(long j, String str);

    private native long createElfObject(String str);

    private native boolean destroyElfObject(long j);

    private native String getInterpreter(long j);

    private native String[] getNeeded(long j);

    private native String getOsAbi(long j);

    private native String[] getRPath(long j);

    private native String getSoName(long j);

    private native boolean isChanged(long j);

    private native boolean removeNeeded(long j, String str);

    private native boolean removeRPath(long j, String str);

    private native boolean replaceOsAbi(long j, String str);

    private native boolean replaceSoName(long j, String str);

    private native boolean setInterpreter(long j, String str);

    static {
        System.loadLibrary("winlator");
    }

    public boolean loadElf(File file) {
        if (this.elfInstancePtr != 0 || !file.exists() || file.isDirectory()) {
            return false;
        }
        this.elfInstancePtr = createElfObject(file.getAbsolutePath());
        if (this.elfInstancePtr == 0) {
            return false;
        }
        this.elfFile = file;
        return true;
    }

    public boolean loadElf(String path) {
        return loadElf(new File(path));
    }

    public void unloadElf() {
        if (this.elfInstancePtr != 0) {
            destroyElfObject(this.elfInstancePtr);
        }
    }

    public boolean saveElf(File file) {
        if (file != this.elfFile && !file.exists()) {
            return true;
        }
        return false;
    }

    public boolean saveElf() {
        if (this.elfFile == null) {
            return false;
        }
        return saveElf(this.elfFile);
    }
}
