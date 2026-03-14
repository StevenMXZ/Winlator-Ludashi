package com.winlator.cmod.xserver.extensions;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadAlloc;
import com.winlator.cmod.xserver.errors.BadDrawable;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import java.io.IOException;
import java.nio.ByteBuffer;

/* loaded from: classes11.dex */
public class DRI3Extension implements Extension {
    public static final byte MAJOR_OPCODE = -102;
    private final Callback<Drawable> onDestroyDrawableListener = new Callback() { // from class: com.winlator.cmod.xserver.extensions.DRI3Extension$$ExternalSyntheticLambda0
        @Override // com.winlator.cmod.core.Callback
        public final void call(Object obj) {
            DRI3Extension.lambda$new$0((Drawable) obj);
        }
    };

    static /* synthetic */ void lambda$new$0(Drawable drawable) {
        ByteBuffer data = drawable.getData();
        SysVSharedMemory.unmapSHMSegment(data, data.capacity());
    }

    private static abstract class ClientOpcodes {
        private static final byte OPEN = 1;
        private static final byte PIXMAP_FROM_BUFFER = 2;
        private static final byte PIXMAP_FROM_BUFFERS = 7;
        private static final byte QUERY_VERSION = 0;

        private ClientOpcodes() {
        }
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public String getName() {
        return "DRI3";
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public byte getFirstErrorId() {
        return (byte) 0;
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public byte getFirstEventId() {
        return (byte) 0;
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private void open(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        inputStream.skip(4);
        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            throw new BadDrawable(drawableId);
        }
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private void pixmapFromBuffer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int size = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short stride = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(1);
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) {
            throw new BadIdChoice(pixmapId);
        }
        int fd = inputStream.getAncillaryFd();
        pixmapFromFd(client, pixmapId, width, height, stride, 0, depth, fd, size);
    }

    private void pixmapFromBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        inputStream.skip(4);
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        int stride = inputStream.readInt();
        int offset = inputStream.readInt();
        inputStream.skip(24);
        byte depth = inputStream.readByte();
        inputStream.skip(3);
        long modifiers = inputStream.readLong();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) {
            throw new BadIdChoice(pixmapId);
        }
        int fd = inputStream.getAncillaryFd();
        long size = stride * height;
        if (modifiers == 1255) {
            pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, fd);
        } else {
            pixmapFromFd(client, pixmapId, width, height, stride, offset, depth, fd, size);
        }
    }

    private void pixmapFromHardwareBuffer(XClient client, int pixmapId, short width, short height, byte depth, int fd) throws IOException, XRequestError {
        try {
            GPUImage gpuImage = new GPUImage(fd);
            Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, gpuImage.getStride(), height, depth);
            drawable.setTexture(gpuImage);
            drawable.setDirectScanout(true);
            client.xServer.pixmapManager.createPixmap(drawable);
        } finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    private void pixmapFromFd(XClient client, int pixmapId, short width, short height, int stride, int offset, byte depth, int fd, long size) throws IOException, XRequestError {
        try {
            ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, true);
            if (buffer == null) {
                throw new BadAlloc();
            }
            short totalWidth = (short) (stride / 4);
            Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, totalWidth, height, depth);
            drawable.setData(buffer);
            drawable.setTexture(null);
            drawable.setOnDestroyListener(this.onDestroyDrawableListener);
            client.xServer.pixmapManager.createPixmap(drawable);
        } finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        XLock lock;
        int opcode = client.getRequestData();
        switch (opcode) {
            case 0:
                queryVersion(client, inputStream, outputStream);
                return;
            case 1:
                XLock lock2 = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER);
                try {
                    open(client, inputStream, outputStream);
                    if (lock2 != null) {
                        lock2.close();
                        return;
                    }
                    return;
                } finally {
                    if (lock2 != null) {
                        try {
                            lock2.close();
                        } catch (Throwable th) {
                            th.addSuppressed(th);
                        }
                    }
                }
            case 2:
                lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER);
                try {
                    pixmapFromBuffer(client, inputStream, outputStream);
                    if (lock != null) {
                        lock.close();
                        return;
                    }
                    return;
                } finally {
                }
            case 7:
                lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER);
                try {
                    pixmapFromBuffers(client, inputStream, outputStream);
                    if (lock != null) {
                        lock.close();
                        return;
                    }
                    return;
                } finally {
                }
            default:
                throw new BadImplementation();
        }
    }
}
