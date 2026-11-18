package com.winlator.cmod.xserver.extensions;

import android.util.Log;
import android.util.SparseArray;
import com.winlator.cmod.renderer.GPUImage;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.core.Callback;
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
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadAlloc;
import com.winlator.cmod.xserver.errors.BadDrawable;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class DRI3Extension implements Extension, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -102;
    private XServer xServer;
    private final SparseArray<DirectContent> directContents = new SparseArray<DirectContent>();
    
    private class DirectContent {
        Window window;
        Pixmap content;
        
        private DirectContent(Window window, Pixmap pixmap) {
            this.window = window;
            this.content = pixmap;
        }
    }

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte OPEN = 1;
        private static final byte PIXMAP_FROM_BUFFER = 2;
        private static final byte PIXMAP_FROM_BUFFERS = 7;
    }
    
    public DRI3Extension(XServer xserver) {
        this.xServer = xserver;
        this.xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "DRI3";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(2);
            outputStream.writePad(16);
        }
    }

    private void open(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
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
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);

        int fd = inputStream.getAncillaryFd();
        pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, fd, window);
    }

    private void pixmapFromBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Log.d("Dri3", "Received pixmap from buffers");
        int pixmapId = inputStream.readInt();
        Log.d("Dri3", "Read pixmap id " + pixmapId);
        int windowId = inputStream.readInt();
        Log.d("Dri3", "Read window id " + windowId);
        inputStream.skip(4);
        short width = inputStream.readShort();
        Log.d("Dri3", "Read width " + width);
        short height = inputStream.readShort();
        Log.d("Dri3", "Read height " + height);
        int stride = inputStream.readInt();
        Log.d("Dri3", "Read stride " + stride);
        int offset = inputStream.readInt();
        Log.d("Dri3", "Read offset " + offset);
        inputStream.skip(24);
        byte depth = inputStream.readByte();
        Log.d("Dri3", "Read depth " + depth);
        inputStream.skip(3);
        long modifiers = inputStream.readLong();
        Log.d("Dri3", "Read modifiers " + modifiers);
        
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);
        
        int fd = inputStream.getAncillaryFd();

        pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, fd, window);
    }
    
    private void pixmapFromHardwareBuffer(XClient client, int pixmapId, short width, short height, byte depth, int fd, Window window) throws IOException, XRequestError {
        try {
            GPUImage gpuImage = new GPUImage(fd);
            Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, width, height, depth, gpuImage);
            drawable.setOnDrawListener(() -> client.xServer.windowManager.triggerOnUpdateWindowContentDirect(window, drawable));
            client.xServer.getXServerView().nativeAddDirectContent(window.id, drawable);
            Pixmap pixmap = client.xServer.pixmapManager.createPixmap(drawable);
            client.registerAsOwnerOfResource(pixmap);
            
            DirectContent directContent = new DirectContent(window, pixmap);
            directContents.put(pixmap.id, directContent);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }   
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.OPEN :
                try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    open(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFER:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffer(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFERS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffers(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            Pixmap pixmap = (Pixmap)resource;
            DirectContent content = directContents.get(pixmap.id);
            if (content != null) {
                xServer.getXServerView().nativeRemoveDirectContent(content.window.id, pixmap.id);
                directContents.remove(pixmap.id);
            }
        }    
    }
}
