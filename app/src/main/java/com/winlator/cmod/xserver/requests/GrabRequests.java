package com.winlator.cmod.xserver.requests;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import java.io.IOException;

/* loaded from: classes4.dex */
public abstract class GrabRequests {

    private enum Status {
        SUCCESS,
        ALREADY_GRABBED,
        INVALID_TIME,
        NOT_VIEWABLE,
        FROZEN
    }

    public static void grabPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Status status;
        XStreamLock lock;
        if (client.xServer.isRelativeMouseMovement()) {
            client.skipRequest();
            lock = outputStream.lock();
            try {
                outputStream.writeByte((byte) 1);
                outputStream.writeByte((byte) Status.ALREADY_GRABBED.ordinal());
                outputStream.writeShort(client.getSequenceNumber());
                outputStream.writeInt(0);
                outputStream.writePad(24);
                if (lock != null) {
                    lock.close();
                    return;
                }
                return;
            } finally {
            }
        }
        boolean ownerEvents = client.getRequestData() == 1;
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        Bitmask eventMask = new Bitmask(inputStream.readShort());
        inputStream.skip(14);
        if (client.xServer.grabManager.getWindow() != null && client.xServer.grabManager.getClient() != client) {
            status = Status.ALREADY_GRABBED;
        } else if (window.getMapState() != Window.MapState.VIEWABLE) {
            status = Status.NOT_VIEWABLE;
        } else {
            status = Status.SUCCESS;
            client.xServer.grabManager.activatePointerGrab(window, ownerEvents, eventMask, client);
        }
        lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) status.ordinal());
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
            if (lock != null) {
                lock.close();
            }
        } finally {
        }
    }

    public static void ungrabPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) {
        inputStream.skip(4);
        client.xServer.grabManager.deactivatePointerGrab();
    }
}
