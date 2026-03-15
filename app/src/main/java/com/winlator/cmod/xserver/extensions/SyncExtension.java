package com.winlator.cmod.xserver.extensions;

import android.util.SparseBooleanArray;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadFence;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;
import java.io.IOException;

/* loaded from: classes11.dex */
public class SyncExtension implements Extension {
    public static final byte MAJOR_OPCODE = -104;
    private final SparseBooleanArray fences = new SparseBooleanArray();
    private final Object fenceLock = new Object();

    private static abstract class ClientOpcodes {
        private static final byte AWAIT_FENCE = 19;
        private static final byte CREATE_FENCE = 14;
        private static final byte DESTROY_FENCE = 17;
        private static final byte RESET_FENCE = 16;
        private static final byte TRIGGER_FENCE = 15;

        private ClientOpcodes() {
        }
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public String getName() {
        return "SYNC";
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

    public void setTriggered(int id) {
        synchronized (this.fenceLock) {
            if (this.fences.indexOfKey(id) >= 0) {
                this.fences.put(id, true);
                this.fenceLock.notifyAll();
            }
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt();
        int id = inputStream.readInt();
        boolean initiallyTriggered = inputStream.readByte() == 1;
        inputStream.skip(3);
        synchronized (this.fenceLock) {
            if (this.fences.indexOfKey(id) >= 0) {
                throw new BadIdChoice(id);
            }
            this.fences.put(id, initiallyTriggered);
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int id = inputStream.readInt();
        synchronized (this.fenceLock) {
            if (this.fences.indexOfKey(id) < 0) {
                throw new BadFence(id);
            }
            this.fences.put(id, true);
            this.fenceLock.notifyAll();
        }
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int id = inputStream.readInt();
        synchronized (this.fenceLock) {
            if (this.fences.indexOfKey(id) < 0) {
                throw new BadFence(id);
            }
            this.fences.put(id, false);
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int id = inputStream.readInt();
        synchronized (this.fenceLock) {
            if (this.fences.indexOfKey(id) < 0) {
                throw new BadFence(id);
            }
            this.fences.delete(id);
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int remainingBytes = client.getRemainingRequestLength();
        if (remainingBytes < 0) {
            remainingBytes = 0;
        }
        int numIds = remainingBytes / 4;
        int[] ids = new int[numIds];
        for (int i = 0; i < numIds; i++) {
            ids[i] = inputStream.readInt();
        }
        int i2 = numIds * 4;
        int leftover = remainingBytes - i2;
        if (leftover > 0) {
            inputStream.skip(leftover);
        }
        if (ids.length == 0) {
            return;
        }
        boolean isNative = client.xServer.getRenderer() != null && client.xServer.getRenderer().isNativeMode();
        boolean anyTriggered = false;
        do {
            synchronized (this.fenceLock) {
                for (int id : ids) {
                    if (this.fences.indexOfKey(id) < 0) {
                        throw new BadFence(id);
                    }
                    anyTriggered = this.fences.get(id);
                    if (anyTriggered) {
                        break;
                    }
                }
                if (!anyTriggered && isNative) {
                    try {
                        this.fenceLock.wait(2L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        } while (!anyTriggered);
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case 14:
                createFence(client, inputStream, outputStream);
                return;
            case 15:
                triggerFence(client, inputStream, outputStream);
                return;
            case 16:
                resetFence(client, inputStream, outputStream);
                return;
            case 17:
                destroyFence(client, inputStream, outputStream);
                return;
            case 18:
            default:
                throw new BadImplementation();
            case 19:
                awaitFence(client, inputStream, outputStream);
                return;
        }
    }
}
