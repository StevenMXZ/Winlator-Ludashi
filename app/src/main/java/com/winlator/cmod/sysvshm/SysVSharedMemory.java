package com.winlator.cmod.sysvshm;

import android.os.Build;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.SparseArray;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/* loaded from: classes11.dex */
public class SysVSharedMemory {
    private final SparseArray<SHMemory> shmemories = new SparseArray<>();
    private int maxSHMemoryId = 0;

    private static native int ashmemCreateRegion(int i, long j);

    public static native int createMemoryFd(String str, int i);

    public static native ByteBuffer mapSHMSegment(int i, long j, int i2, boolean z);

    public static native void unmapSHMSegment(ByteBuffer byteBuffer, long j);

    static {
        System.loadLibrary("winlator");
    }

    private static class SHMemory {
        private ByteBuffer data;
        private int fd;
        private long size;

        private SHMemory() {
        }
    }

    public int getFd(int shmid) {
        int i;
        synchronized (this.shmemories) {
            SHMemory shmemory = this.shmemories.get(shmid);
            i = shmemory != null ? shmemory.fd : -1;
        }
        return i;
    }

    public int get(long size) {
        synchronized (this.shmemories) {
            int index = this.shmemories.size();
            int fd = ashmemCreateRegion(index, size);
            if (fd < 0) {
                fd = createSharedMemory("sysvshm-" + index, (int) size);
            }
            if (fd < 0) {
                return -1;
            }
            SHMemory shmemory = new SHMemory();
            int id = this.maxSHMemoryId + 1;
            this.maxSHMemoryId = id;
            shmemory.fd = fd;
            shmemory.size = size;
            this.shmemories.put(id, shmemory);
            return id;
        }
    }

    public void delete(int shmid) {
        SHMemory shmemory = this.shmemories.get(shmid);
        if (shmemory != null) {
            if (shmemory.fd != -1) {
                XConnectorEpoll.closeFd(shmemory.fd);
                shmemory.fd = -1;
            }
            this.shmemories.remove(shmid);
        }
    }

    public void deleteAll() {
        synchronized (this.shmemories) {
            for (int i = this.shmemories.size() - 1; i >= 0; i--) {
                delete(this.shmemories.keyAt(i));
            }
        }
    }

    public ByteBuffer attach(int shmid) {
        synchronized (this.shmemories) {
            SHMemory shmemory = this.shmemories.get(shmid);
            if (shmemory == null) {
                return null;
            }
            if (shmemory.data == null) {
                shmemory.data = mapSHMSegment(shmemory.fd, shmemory.size, 0, false);
            }
            return shmemory.data;
        }
    }

    public void detach(ByteBuffer data) {
        synchronized (this.shmemories) {
            int i = 0;
            while (true) {
                if (i >= this.shmemories.size()) {
                    break;
                }
                SHMemory shmemory = this.shmemories.valueAt(i);
                if (shmemory.data != data) {
                    i++;
                } else if (shmemory.data != null) {
                    unmapSHMSegment(shmemory.data, shmemory.size);
                    shmemory.data = null;
                }
            }
        }
    }

    private static int createSharedMemory(String name, int size) {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                SharedMemory sharedMemory = SharedMemory.create(name, size);
                try {
                    Method method = sharedMemory.getClass().getMethod("getFd", new Class[0]);
                    Object ret = method.invoke(sharedMemory, new Object[0]);
                    if (ret != null) {
                        return ((Integer) ret).intValue();
                    }
                    return -1;
                } catch (IllegalAccessException e) {
                    return -1;
                } catch (NoSuchMethodException e2) {
                    return -1;
                } catch (InvocationTargetException e3) {
                    return -1;
                }
            }
            return -1;
        } catch (ErrnoException e4) {
            return -1;
        }
    }
}
