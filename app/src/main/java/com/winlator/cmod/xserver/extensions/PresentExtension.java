package com.winlator.cmod.xserver.extensions;

import android.util.SparseArray;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.DrawableManager$$ExternalSyntheticLambda0;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.PresentCompleteNotify;
import com.winlator.cmod.xserver.events.PresentIdleNotify;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Objects;

/* loaded from: classes11.dex */
public class PresentExtension implements Extension {
    public static final byte MAJOR_OPCODE = -103;
    private final SparseArray<Event> events = new SparseArray<>();
    private long nextFrameTime = 0;
    private SyncExtension syncExtension;

    public enum Kind {
        PIXMAP,
        MSC_NOTIFY
    }

    public enum Mode {
        COPY,
        FLIP,
        SKIP
    }

    private void enforceAbsoluteFramerate(GLRenderer renderer) {
        if (renderer == null || renderer.isNativeMode()) {
            this.nextFrameTime = 0L;
            return;
        }
        int targetFps = renderer.getFpsLimit();
        if (targetFps <= 0) {
            this.nextFrameTime = 0L;
            return;
        }
        long targetFrameTime = 1000000000 / targetFps;
        long now = System.nanoTime();
        if (this.nextFrameTime == 0 || now > this.nextFrameTime) {
            this.nextFrameTime = now;
        }
        long sleepTime = this.nextFrameTime - now;
        if (sleepTime > 0) {
            long sleepMs = (sleepTime - 1500000) / 1000000;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                }
            }
            while (System.nanoTime() < this.nextFrameTime) {
            }
        }
        this.nextFrameTime += targetFrameTime;
    }

    private static abstract class ClientOpcodes {
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte QUERY_VERSION = 0;
        private static final byte SELECT_INPUT = 3;

        private ClientOpcodes() {
        }
    }

    private static class Event {
        private XClient client;
        private int id;
        private Bitmask mask;
        private Window window;

        private Event() {
        }
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public String getName() {
        return "Present";
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

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) {
            this.syncExtension.setTriggered(idleFence);
        }
        synchronized (this.events) {
            for (int i = 0; i < this.events.size(); i++) {
                Event event = this.events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        PresentExtension presentExtension = this;
        synchronized (presentExtension.events) {
            int i = 0;
            while (i < presentExtension.events.size()) {
                try {
                    Event event = presentExtension.events.valueAt(i);
                    if (event.window == window) {
                        try {
                            if (event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                                event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                            }
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                    i++;
                    presentExtension = this;
                } catch (Throwable th2) {
                    th = th2;
                }
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
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

    private void triggerDrawListener(Drawable content) {
        try {
            Field field = Drawable.class.getDeclaredField("onDrawListener");
            field.setAccessible(true);
            Runnable listener = (Runnable) field.get(content);
            if (listener != null) {
                listener.run();
            }
        } catch (Exception e) {
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Object obj;
        boolean z;
        boolean isNative;
        Object obj2;
        Window window;
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());
        Window window2 = client.xServer.windowManager.getWindow(windowId);
        if (window2 == null) {
            throw new BadWindow(windowId);
        }
        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) {
            throw new BadPixmap(pixmapId);
        }
        Drawable content = window2.getContent();
        if (content.visual.depth != pixmap.drawable.visual.depth) {
            throw new BadMatch();
        }
        long ust = System.nanoTime() / 1000;
        long msc = ust / 16666;
        Object obj3 = content.renderLock;
        synchronized (obj3) {
            try {
                try {
                    GLRenderer renderer = client.xServer.getRenderer();
                    if (renderer != null) {
                        try {
                            if (renderer.isNativeMode()) {
                                z = true;
                                isNative = z;
                                if (isNative || !pixmap.drawable.isDirectScanout()) {
                                    obj2 = obj3;
                                    window = window2;
                                    content.copyArea((short) 0, (short) 0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
                                    sendIdleNotify(window, pixmap, serial, idleFence);
                                    sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
                                } else {
                                    content.setTexture(pixmap.drawable.getTexture());
                                    triggerDrawListener(content);
                                    sendIdleNotify(window2, pixmap, serial, idleFence);
                                    obj2 = obj3;
                                    window = window2;
                                    sendCompleteNotify(window2, serial, Kind.PIXMAP, Mode.FLIP, ust, msc);
                                }
                                if (window.attributes.isMapped() && renderer != null) {
                                    renderer.onUpdateWindowContent(window);
                                }
                            }
                        } catch (Throwable th) {
                            th = th;
                            obj = obj3;
                            throw th;
                        }
                    }
                    z = false;
                    isNative = z;
                    if (isNative) {
                    }
                    obj2 = obj3;
                    window = window2;
                    content.copyArea((short) 0, (short) 0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
                    sendIdleNotify(window, pixmap, serial, idleFence);
                    sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
                    if (window.attributes.isMapped()) {
                        renderer.onUpdateWindowContent(window);
                    }
                } catch (Throwable th2) {
                    th = th2;
                    obj = obj3;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) {
            throw new BadWindow(windowId);
        }
        if (GPUImage.isSupported() && !mask.isEmpty()) {
            Drawable content = window.getContent();
            Texture oldTexture = content.getTexture();
            XServerView xServerView = client.xServer.getRenderer().xServerView;
            Objects.requireNonNull(oldTexture);
            xServerView.queueEvent(new DrawableManager$$ExternalSyntheticLambda0(oldTexture));
            content.setTexture(new GPUImage(content.width, content.height));
        }
        synchronized (this.events) {
            Event event = this.events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) {
                    throw new BadMatch();
                }
                if (mask.isEmpty()) {
                    this.events.remove(eventId);
                } else {
                    event.mask = mask;
                }
            } else {
                Event event2 = new Event();
                event2.id = eventId;
                event2.window = window;
                event2.client = client;
                event2.mask = mask;
                this.events.put(eventId, event2);
            }
        }
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        XLock lock;
        int opcode = client.getRequestData();
        if (this.syncExtension == null) {
            this.syncExtension = (SyncExtension) client.xServer.getExtension(-104);
        }
        switch (opcode) {
            case 0:
                queryVersion(client, inputStream, outputStream);
                return;
            case 1:
                lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER);
                try {
                    presentPixmap(client, inputStream, outputStream);
                    if (lock != null) {
                        lock.close();
                    }
                    enforceAbsoluteFramerate(client.xServer.getRenderer());
                    return;
                } finally {
                }
            case 2:
            default:
                throw new BadImplementation();
            case 3:
                lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER);
                try {
                    selectInput(client, inputStream, outputStream);
                    if (lock != null) {
                        lock.close();
                        return;
                    }
                    return;
                } finally {
                }
        }
    }
}
