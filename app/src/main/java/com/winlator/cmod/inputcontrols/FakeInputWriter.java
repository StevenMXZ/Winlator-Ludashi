package com.winlator.cmod.inputcontrols;

import android.util.Log;

import java.io.File;
import java.io.IOException;

public final class FakeInputWriter {
    private static final String TAG = "FakeInputWriter";

    static {
        System.loadLibrary("winlator");
    }

    private final File eventFile;
    private final int slot;
    private volatile boolean isOpen = false;
    private volatile boolean destroyed = false;

    public FakeInputWriter(String fakeInputPath, int slot) {
        this.eventFile = new File(fakeInputPath, "event" + slot);
        this.slot = slot;
    }

    public static synchronized boolean prepareSharedMemory(String fakeInputPath, int slotCount) {
        boolean prepared = nativePrepareSharedMemory(fakeInputPath, slotCount);
        if (!prepared) {
            Log.e(TAG, "Failed to prepare ASharedMemory fake-input transport");
        }
        return prepared;
    }

    public static synchronized void shutdownSharedMemory() {
        nativeShutdownSharedMemory();
    }

    public synchronized boolean open() {
        if (destroyed) return false;
        if (isOpen) return true;

        try {
            File parent = eventFile.getParentFile();
            if (parent != null) parent.mkdirs();

            // Keep the same stable discovery node behavior as the Vulkan UI branch.
            // Wine may open eventN before the touchscreen/physical controller starts
            // publishing; the ASharedMemory ring is only the data path behind that fd.
            if (!eventFile.exists() && !eventFile.createNewFile()) {
                Log.e(TAG, "Failed to create fake input discovery node: " + eventFile);
                return false;
            }

            if (!nativeActivate(slot)) {
                Log.e(TAG, "Failed to activate ASharedMemory fake input slot " + slot);
                return false;
            }

            isOpen = true;
            Log.i(TAG, "Opened ASharedMemory fake input slot " + slot + ": " + eventFile);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to open fake input slot " + slot + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized void close() {
        isOpen = false;
    }

    public synchronized void reset() {
        if (!isOpen && !open()) return;
        nativeReset(slot);
    }

    public synchronized void requestFullResend() {
        if (!isOpen && !open()) return;
        nativeRequestFullResend(slot);
    }

    public synchronized void softRelease() {
        if (destroyed) return;
        if (isOpen) nativeReset(slot);
        isOpen = false;
    }

    public synchronized void destroy() {
        if (destroyed) return;

        if (isOpen) nativeReset(slot);
        nativeDeactivate(slot);
        isOpen = false;
        destroyed = true;

        if (eventFile.exists()) {
            boolean deleted = eventFile.delete();
            Log.i(TAG, "Deleted fake input discovery node " + eventFile + " (" + deleted + ")");
        }
    }

    public void writeGamepadState(GamepadState state) {
        if (state == null) return;
        if (!isOpen && !open()) return;

        int dpadMask = 0;
        if (state.dpad[0]) dpadMask |= 1;
        if (state.dpad[1]) dpadMask |= 2;
        if (state.dpad[2]) dpadMask |= 4;
        if (state.dpad[3]) dpadMask |= 8;

        nativePublishGamepadState(
                slot,
                state.buttons & 0xffff,
                state.thumbLX,
                state.thumbLY,
                state.thumbRX,
                state.thumbRY,
                state.triggerL,
                state.triggerR,
                dpadMask
        );
    }

    public boolean isOpen() {
        return isOpen;
    }

    private static native boolean nativePrepareSharedMemory(String fakeInputPath, int slotCount);
    private static native boolean nativeActivate(int slot);
    private static native void nativeDeactivate(int slot);
    private static native void nativeReset(int slot);
    private static native void nativeRequestFullResend(int slot);
    private static native void nativePublishGamepadState(
            int slot,
            int buttons,
            float thumbLX,
            float thumbLY,
            float thumbRX,
            float thumbRY,
            float triggerL,
            float triggerR,
            int dpadMask
    );
    private static native void nativeShutdownSharedMemory();
}
