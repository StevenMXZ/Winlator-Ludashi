package com.winlator.cmod.renderer;

import com.winlator.cmod.xserver.Drawable;
import java.nio.ByteBuffer;

/* loaded from: classes12.dex */
public class GPUImage extends Texture {
    private static boolean supported = false;
    private long hardwareBufferPtr;
    private long imageKHRPtr;
    private short stride;
    private ByteBuffer virtualData;

    private native long createHardwareBuffer(short s, short s2);

    private native long createImageKHR(long j, int i);

    private native void destroyHardwareBuffer(long j);

    private native void destroyImageKHR(long j);

    private native long hardwareBufferFromSocket(int i);

    private native ByteBuffer lockHardwareBuffer(long j);

    static {
        System.loadLibrary("winlator");
    }

    public GPUImage(short width, short height) {
        this.hardwareBufferPtr = createHardwareBuffer(width, height);
        if (this.hardwareBufferPtr != 0) {
            this.virtualData = lockHardwareBuffer(this.hardwareBufferPtr);
            if (this.virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(this.hardwareBufferPtr);
                this.hardwareBufferPtr = 0L;
                return;
            }
            return;
        }
        System.err.println("Error: Failed to create hardware buffer");
    }

    public GPUImage(int socketFd) {
        this.hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        if (this.hardwareBufferPtr != 0) {
            this.virtualData = lockHardwareBuffer(this.hardwareBufferPtr);
            if (this.virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(this.hardwareBufferPtr);
                this.hardwareBufferPtr = 0L;
                return;
            }
            return;
        }
        System.err.println("Error: Failed to create hardware buffer");
    }

    @Override // com.winlator.cmod.renderer.Texture
    public void allocateTexture(short width, short height, ByteBuffer data) {
        if (isAllocated()) {
            return;
        }
        super.allocateTexture(width, height, null);
        if (this.hardwareBufferPtr != 0) {
            this.imageKHRPtr = createImageKHR(this.hardwareBufferPtr, this.textureId);
            if (this.imageKHRPtr == 0) {
                System.err.println("Error: Failed to create EGL image");
                destroyHardwareBuffer(this.hardwareBufferPtr);
                this.hardwareBufferPtr = 0L;
            }
        }
    }

    @Override // com.winlator.cmod.renderer.Texture
    public void updateFromDrawable(Drawable drawable) {
        if (!isAllocated()) {
            allocateTexture(drawable.width, drawable.height, null);
        }
        this.needsUpdate = false;
    }

    public short getStride() {
        return this.stride;
    }

    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return this.virtualData;
    }

    @Override // com.winlator.cmod.renderer.Texture
    public void destroy() {
        if (this.imageKHRPtr != 0) {
            destroyImageKHR(this.imageKHRPtr);
            this.imageKHRPtr = 0L;
        }
        if (this.hardwareBufferPtr != 0) {
            destroyHardwareBuffer(this.hardwareBufferPtr);
            this.hardwareBufferPtr = 0L;
        }
        this.virtualData = null;
        super.destroy();
    }

    public static boolean isSupported() {
        return supported;
    }

    public static void checkIsSupported() {
        GPUImage gpuImage = new GPUImage((short) 8, (short) 8);
        gpuImage.allocateTexture((short) 8, (short) 8, null);
        supported = (gpuImage.hardwareBufferPtr == 0 || gpuImage.imageKHRPtr == 0 || gpuImage.virtualData == null) ? false : true;
        gpuImage.destroy();
    }
}
