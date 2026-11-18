package com.winlator.cmod.renderer;

import android.util.Log;
import androidx.annotation.Keep;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import dalvik.annotation.optimization.FastNative;
import java.nio.ByteBuffer;

public class GPUImage {
    private static final String LOG_TAG = "GPUImage";
    
    public long hardwareBufferPtr;
    public int format;
    private ByteBuffer virtualData;
    private short stride;
    private boolean locked = false;
    private static boolean supported = false;

    static {
        System.loadLibrary("winlator");
    }

    public GPUImage(short width, short height, int format) {
        hardwareBufferPtr = createHardwareBuffer(width, height, format);
        if (hardwareBufferPtr == 0)
            Log.d(LOG_TAG, "Failed to create hardware buffer");
    }
    
    public GPUImage(int socketFd) {
        hardwareBufferPtr = nativeHardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr == 0)
            Log.d(LOG_TAG, "Failed to create hardware buffer");
    }
    
    public void lock() {
        if (locked)
            return;
            
        virtualData = lockHardwareBuffer(hardwareBufferPtr);
        if (virtualData == null) {
            Log.d(LOG_TAG, "Failed to lock hardware buffer");
            return;
        }
        
        locked = true;
    }
    
    public void unlock() {
        unlockHardwareBuffer(hardwareBufferPtr);
        locked = false;
        virtualData = null;
    }

    public short getStride() {
        return stride;
    }
    
    public void destroy() {
        if (locked)
            unlockHardwareBuffer(hardwareBufferPtr);
            
        destroyHardwareBuffer(hardwareBufferPtr);
        hardwareBufferPtr = 0;
    }
    
    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }
    
    public long getAHB() {
        return hardwareBufferPtr;
    }

    public static boolean isSupported() {
        return supported;
    }
    
    @FastNative
    private native long nativeHardwareBufferFromSocket(int fd);
    @FastNative
    private native long createHardwareBuffer(short width, short height, int format);
    @FastNative
    private native void destroyHardwareBuffer(long hardwareBufferPtr);
    @FastNative
    private native void unlockHardwareBuffer(long hardwareBufferPtr);
    @FastNative
    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);
}
    
