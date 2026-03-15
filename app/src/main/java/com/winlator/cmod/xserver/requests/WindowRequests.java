package com.winlator.cmod.xserver.requests;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.Visual;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadAccess;
import com.winlator.cmod.xserver.errors.BadDrawable;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.CreateNotify;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.RawEvent;
import java.io.IOException;
import java.util.List;

/* loaded from: classes4.dex */
public abstract class WindowRequests {
    public static void createWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        byte depth = client.getRequestData();
        int windowId = inputStream.readInt();
        int parentId = inputStream.readInt();
        if (!client.isValidResourceId(windowId)) {
            throw new BadIdChoice(windowId);
        }
        Window parent = client.xServer.windowManager.getWindow(parentId);
        if (parent == null) {
            throw new BadWindow(parentId);
        }
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short borderWidth = inputStream.readShort();
        WindowAttributes.WindowClass windowClass = WindowAttributes.WindowClass.values()[(byte) inputStream.readShort()];
        Visual visual = client.xServer.pixmapManager.getVisual(inputStream.readInt());
        Bitmask valueMask = new Bitmask(inputStream.readInt());
        Window window = client.xServer.windowManager.createWindow(windowId, parent, x, y, width, height, windowClass, visual, depth, client);
        window.setBorderWidth(borderWidth);
        if (!valueMask.isEmpty()) {
            window.attributes.update(valueMask, inputStream, client);
        }
        client.setEventListenerForWindow(window, window.attributes.getEventMask());
        client.registerAsOwnerOfResource(window);
        parent.sendEvent(524288, new CreateNotify(parent, window));
    }

    public static void getWindowAttributes(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        XStreamLock lock = outputStream.lock();
        int i = 1;
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) window.attributes.getBackingStore().ordinal());
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(3);
            outputStream.writeInt(window.isInputOutput() ? window.getContent().visual.id : 0);
            outputStream.writeShort((short) window.attributes.getWindowClass().ordinal());
            outputStream.writeByte((byte) window.attributes.getBitGravity().ordinal());
            outputStream.writeByte((byte) window.attributes.getWinGravity().ordinal());
            outputStream.writeInt(window.attributes.getBackingPlanes());
            outputStream.writeInt(window.attributes.getBackingPixel());
            outputStream.writeByte((byte) (window.attributes.isSaveUnder() ? 1 : 0));
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) window.getMapState().ordinal());
            if (!window.attributes.isOverrideRedirect()) {
                i = 0;
            }
            outputStream.writeByte((byte) i);
            outputStream.writeInt(0);
            outputStream.writeInt(window.getAllEventMasks().getBits());
            outputStream.writeInt(client.getEventMaskForWindow(window).getBits());
            outputStream.writeShort((short) window.attributes.getDoNotPropagateMask().getBits());
            outputStream.writeShort((short) 0);
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

    public static void changeWindowAttributes(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        Bitmask valueMask = new Bitmask(inputStream.readInt());
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        if (!valueMask.isEmpty()) {
            window.attributes.update(valueMask, inputStream, client);
            if (valueMask.isSet(2048)) {
                if (isClientCanSelectFor(1048576, window, client) && isClientCanSelectFor(262144, window, client) && isClientCanSelectFor(4, window, client)) {
                    client.setEventListenerForWindow(window, window.attributes.getEventMask());
                    return;
                }
                throw new BadAccess();
            }
        }
    }

    private static boolean isClientCanSelectFor(int eventId, Window window, XClient client) {
        return (window.attributes.getEventMask().isSet(eventId) && window.hasEventListenerFor(eventId) && !client.isInterestedIn(eventId, window)) ? false : true;
    }

    public static void destroyWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) {
        client.xServer.windowManager.destroyWindow(inputStream.readInt());
    }

    public static void destroySubWindows(XClient client, XInputStream inputStream, XOutputStream outputStream) {
        client.xServer.windowManager.destroyWindow(inputStream.readInt());
    }

    public static void reparentWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        int parentId = inputStream.readInt();
        inputStream.skip(4);
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        Window parent = client.xServer.windowManager.getWindow(parentId);
        if (parent == null) {
            throw new BadWindow(parentId);
        }
        client.xServer.windowManager.reparentWindow(window, parent);
    }

    public static void mapWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        client.xServer.windowManager.mapWindow(window);
    }

    public static void mapSubWindows(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        for (Window child : window.getChildren()) {
            mapSubWindows(client, child.id);
        }
        client.xServer.windowManager.mapWindow(window);
    }

    private static void mapSubWindows(XClient client, int windowId) throws XRequestError {
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        for (Window child : window.getChildren()) {
            mapSubWindows(client, child.id);
        }
        client.xServer.windowManager.mapWindow(window);
    }

    public static void unmapWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        client.xServer.windowManager.unmapWindow(window);
    }

    public static void changeProperty(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        byte[] data;
        Property.Mode mode = Property.Mode.values()[client.getRequestData()];
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        int atom = inputStream.readInt();
        int type = inputStream.readInt();
        byte format = inputStream.readByte();
        inputStream.skip(3);
        int length = inputStream.readInt();
        int totalSize = length * (format >> 3);
        if (totalSize <= 0) {
            data = null;
        } else {
            byte[] data2 = new byte[totalSize];
            inputStream.read(data2);
            inputStream.skip(3 & (-totalSize));
            data = data2;
        }
        Property property = window.modifyProperty(atom, type, Property.Format.valueOf(format), mode, data);
        if (property == null) {
            throw new BadMatch();
        }
        client.xServer.windowManager.triggerOnModifyWindowProperty(window, property);
    }

    public static void deleteProperty(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        window.removeProperty(inputStream.readInt());
    }

    public static void getProperty(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        boolean delete = client.getRequestData() == 1;
        short sequenceNumber = client.getSequenceNumber();
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        int atom = inputStream.readInt();
        int type = inputStream.readInt();
        int longOffset = inputStream.readInt();
        int longLength = inputStream.readInt();
        Property property = window.getProperty(atom);
        int bytesAfter = 0;
        XStreamLock lock = outputStream.lock();
        try {
            if (property == null) {
                outputStream.writeByte((byte) 1);
                outputStream.writeByte((byte) 0);
                outputStream.writeShort(sequenceNumber);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writePad(12);
            } else if (property.type != type && type != 0) {
                outputStream.writeByte((byte) 1);
                outputStream.writeByte(property.format.value);
                outputStream.writeShort(sequenceNumber);
                outputStream.writeInt(0);
                outputStream.writeInt(property.type);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writePad(12);
            } else {
                byte[] data = property.data.array();
                int offset = longOffset * 4;
                int length = Math.min(data.length - offset, longLength * 4);
                if (length < 0) {
                    throw new BadValue(longOffset);
                }
                bytesAfter = data.length - (offset + length);
                outputStream.writeByte((byte) 1);
                outputStream.writeByte(property.format.value);
                outputStream.writeShort(sequenceNumber);
                outputStream.writeInt((length + 3) / 4);
                outputStream.writeInt(property.type);
                outputStream.writeInt(bytesAfter);
                outputStream.writeInt(length / (property.format.value / 8));
                outputStream.writePad(12);
                outputStream.write(data, offset, length);
                if (((-length) & 3) > 0) {
                    outputStream.writePad((-length) & 3);
                }
            }
            if (lock != null) {
                lock.close();
            }
            if (delete && property != null && bytesAfter == 0) {
                window.removeProperty(atom);
            }
        } finally {
        }
    }

    public static void queryPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        short rootX = client.xServer.pointer.getClampedX();
        short rootY = client.xServer.pointer.getClampedY();
        Window child = window.getChildByCoords(rootX, rootY);
        short[] localPoint = window.rootPointToLocal(rootX, rootY);
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) (!client.xServer.isRelativeMouseMovement() ? 1 : 0));
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(client.xServer.windowManager.rootWindow.id);
            outputStream.writeInt(child != null ? child.id : 0);
            outputStream.writeShort(rootX);
            outputStream.writeShort(rootY);
            outputStream.writeShort(localPoint[0]);
            outputStream.writeShort(localPoint[1]);
            outputStream.writeShort((short) client.xServer.inputDeviceManager.getKeyButMask().getBits());
            outputStream.writePad(6);
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

    public static void translateCoordinates(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int srcWindowId = inputStream.readInt();
        int dstWindowId = inputStream.readInt();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        Window srcWindow = client.xServer.windowManager.getWindow(srcWindowId);
        Window dstWindow = client.xServer.windowManager.getWindow(dstWindowId);
        if (srcWindow == null) {
            throw new BadWindow(srcWindowId);
        }
        if (dstWindow == null) {
            throw new BadWindow(dstWindowId);
        }
        short[] rootPoint = srcWindow.localPointToRoot(srcX, srcY);
        short[] localPoint = dstWindow.rootPointToLocal(rootPoint[0], rootPoint[1]);
        Window child = dstWindow.getChildByCoords(rootPoint[0], rootPoint[1]);
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) 1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(child != null ? child.id : 0);
            outputStream.writeShort(localPoint[0]);
            outputStream.writeShort(localPoint[1]);
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

    public static void warpPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        if (client.xServer.isRelativeMouseMovement()) {
            client.skipRequest();
            return;
        }
        Window srcWindow = client.xServer.windowManager.getWindow(inputStream.readInt());
        Window dstWindow = client.xServer.windowManager.getWindow(inputStream.readInt());
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short srcWidth = inputStream.readShort();
        short srcHeight = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        if (srcWindow != null) {
            if (srcWidth == 0) {
                srcWidth = (short) (srcWindow.getWidth() - srcX);
            }
            if (srcHeight == 0) {
                srcHeight = (short) (srcWindow.getHeight() - srcY);
            }
            short[] localPoint = srcWindow.rootPointToLocal(client.xServer.pointer.getX(), client.xServer.pointer.getY());
            boolean isContained = localPoint[0] >= srcX && localPoint[1] >= srcY && localPoint[0] < srcX + srcWidth && localPoint[1] < srcY + srcHeight;
            if (!isContained) {
                return;
            }
        }
        if (dstWindow == null) {
            client.xServer.pointer.setX(client.xServer.pointer.getX() + dstX);
            client.xServer.pointer.setY(client.xServer.pointer.getY() + dstY);
        } else {
            short[] localPoint2 = dstWindow.localPointToRoot(dstX, dstY);
            client.xServer.pointer.setX(localPoint2[0]);
            client.xServer.pointer.setY(localPoint2[1]);
        }
    }

    public static void setInputFocus(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        WindowManager.FocusRevertTo focusRevertTo = WindowManager.FocusRevertTo.values()[client.getRequestData()];
        int windowId = inputStream.readInt();
        inputStream.skip(4);
        switch (focusRevertTo) {
            case NONE:
                client.xServer.windowManager.setFocus(null, focusRevertTo);
                return;
            case POINTER_ROOT:
                client.xServer.windowManager.setFocus(client.xServer.windowManager.rootWindow, focusRevertTo);
                return;
            case PARENT:
                Window window = client.xServer.windowManager.getWindow(windowId);
                if (window == null) {
                    throw new BadWindow(windowId);
                }
                client.xServer.windowManager.setFocus(window, focusRevertTo);
                return;
            default:
                return;
        }
    }

    public static void getInputFocus(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Window focusedWindow = client.xServer.windowManager.getFocusedWindow();
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) client.xServer.windowManager.getFocusRevertTo().ordinal());
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(focusedWindow != null ? focusedWindow.id : 0);
            outputStream.writePad(20);
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

    public static void configureWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        Bitmask valueMask = new Bitmask(inputStream.readShort());
        inputStream.skip(2);
        if (!valueMask.isEmpty()) {
            client.xServer.windowManager.configureWindow(window, valueMask, inputStream);
        }
    }

    public static void getGeometry(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            throw new BadDrawable(drawableId);
        }
        Window window = client.xServer.windowManager.getWindow(drawableId);
        short x = window != null ? window.getX() : (short) 0;
        short y = window != null ? window.getY() : (short) 0;
        short borderWidth = window != null ? window.getBorderWidth() : (short) 0;
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte(drawable.visual.depth);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(client.xServer.windowManager.rootWindow.id);
            outputStream.writeShort(x);
            outputStream.writeShort(y);
            outputStream.writeShort(drawable.width);
            outputStream.writeShort(drawable.height);
            outputStream.writeShort(borderWidth);
            outputStream.writePad(10);
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

    public static void queryTree(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        Window parent = window.getParent();
        List<Window> children = window.getChildren();
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(children.size());
            outputStream.writeInt(client.xServer.windowManager.rootWindow.id);
            outputStream.writeInt(parent != null ? parent.id : 0);
            outputStream.writeShort((short) children.size());
            outputStream.writePad(14);
            for (int i = children.size() - 1; i >= 0; i--) {
                outputStream.writeInt(children.get(i).id);
            }
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

    public static void sendEvent(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        if (windowId == 0 || windowId == 1) {
            client.skipRequest();
            return;
        }
        Window destination = client.xServer.windowManager.getWindow(windowId);
        if (destination == null) {
            throw new BadWindow(windowId);
        }
        Bitmask eventMask = new Bitmask(inputStream.readInt());
        byte[] data = new byte[32];
        inputStream.read(data);
        Event event = new RawEvent(data);
        if (eventMask.isEmpty()) {
            destination.originClient.sendEvent(event);
        } else {
            destination.sendEvent(eventMask, event);
        }
    }

    public static void getScreenSaver(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short) 600);
            outputStream.writeShort((short) 600);
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) 1);
            outputStream.writePad(18);
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
}
