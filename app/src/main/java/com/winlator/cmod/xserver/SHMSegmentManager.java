package com.winlator.cmod.xserver;

import android.util.SparseArray;
import com.winlator.cmod.sysvshm.SysVSharedMemory;
import java.nio.ByteBuffer;

/* loaded from: classes11.dex */
public class SHMSegmentManager {
    private final SparseArray<ByteBuffer> shmSegments = new SparseArray<>();
    private final SysVSharedMemory sysVSharedMemory;

    public SHMSegmentManager(SysVSharedMemory sysVSharedMemory) {
        this.sysVSharedMemory = sysVSharedMemory;
    }

    public void attach(int xid, int shmid) {
        if (this.shmSegments.indexOfKey(xid) >= 0) {
            detach(xid);
        }
        ByteBuffer data = this.sysVSharedMemory.attach(shmid);
        if (data != null) {
            this.shmSegments.put(xid, data);
        }
    }

    public void detach(int xid) {
        ByteBuffer data = this.shmSegments.get(xid);
        if (data != null) {
            this.sysVSharedMemory.detach(data);
            this.shmSegments.remove(xid);
        }
    }

    public ByteBuffer getData(int xid) {
        return this.shmSegments.get(xid);
    }
}
