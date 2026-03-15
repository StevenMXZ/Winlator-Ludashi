package com.winlator.cmod.winhandler;

import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.FakeInputWriter;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.xserver.XServer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/* loaded from: classes12.dex */
public class WinHandler {
    private static final short CLIENT_PORT = 7946;
    public static final byte DEFAULT_INPUT_TYPE = 4;
    public static final byte FLAG_INPUT_TYPE_DINPUT = 8;
    public static final byte FLAG_INPUT_TYPE_XINPUT = 4;
    private static final int MAX_CONTROLLERS = 4;
    private static final int OSC_DEVICE_ID = -1;
    private static final short SERVER_PORT = 7947;
    private final XServerDisplayActivity activity;
    private String fakeInputBasePath;
    private final InputManager inputManager;
    private InetAddress localhost;
    private OnGetProcessInfoListener onGetProcessInfoListener;
    private SharedPreferences preferences;
    private DatagramSocket socket;
    private boolean xinputDisabled;
    private final ByteBuffer sendData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer receiveData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket sendPacket = new DatagramPacket(this.sendData.array(), 64);
    private final DatagramPacket receivePacket = new DatagramPacket(this.receiveData.array(), 64);
    private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private boolean initReceived = false;
    private boolean running = false;
    private final Map<Integer, ExternalController> controllers = new HashMap();
    private byte inputType = 4;
    private final List<Integer> gamepadClients = new CopyOnWriteArrayList();
    private FakeInputWriter[] writers = new FakeInputWriter[4];
    private Map<Integer, Integer> deviceToSlot = new HashMap();
    private Set<Integer> usedSlots = new HashSet();
    private boolean xinputDisabledInitialized = false;
    private final InputManager.InputDeviceListener inputDeviceListener = new InputManager.InputDeviceListener() { // from class: com.winlator.cmod.winhandler.WinHandler.1
        @Override // android.hardware.input.InputManager.InputDeviceListener
        public void onInputDeviceAdded(int deviceId) {
        }

        @Override // android.hardware.input.InputManager.InputDeviceListener
        public void onInputDeviceRemoved(int deviceId) {
            WinHandler.this.releaseSlot(deviceId);
        }

        @Override // android.hardware.input.InputManager.InputDeviceListener
        public void onInputDeviceChanged(int deviceId) {
        }
    };

    public WinHandler(XServerDisplayActivity activity) {
        this.activity = activity;
        this.inputManager = (InputManager) activity.getSystemService("input");
        this.inputManager.registerInputDeviceListener(this.inputDeviceListener, null);
        this.preferences = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());
    }

    private boolean sendPacket(int port) {
        try {
            int size = this.sendData.position();
            if (size == 0) {
                return false;
            }
            this.sendPacket.setAddress(this.localhost);
            this.sendPacket.setPort(port);
            this.socket.send(this.sendPacket);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /* renamed from: exec, reason: merged with bridge method [inline-methods] */
    public void lambda$execWithDelay$10(String command) {
        final String filename;
        final String parameters;
        String command2 = command.trim();
        if (command2.isEmpty()) {
            return;
        }
        if (command2.contains("\"")) {
            int firstQuote = command2.indexOf("\"");
            int lastQuote = command2.lastIndexOf("\"");
            filename = command2.substring(firstQuote + 1, lastQuote);
            if (lastQuote + 1 < command2.length()) {
                parameters = command2.substring(lastQuote + 1).trim();
            } else {
                parameters = "";
            }
        } else {
            String[] cmdList = command2.split(" ", 2);
            filename = cmdList[0];
            if (cmdList.length > 1) {
                parameters = cmdList[1];
            } else {
                parameters = "";
            }
        }
        addAction(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$exec$0(filename, parameters);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$exec$0(String filename, String parameters) {
        byte[] filenameBytes = filename.getBytes();
        byte[] parametersBytes = parameters.getBytes();
        this.sendData.rewind();
        this.sendData.put((byte) 2);
        this.sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
        this.sendData.putInt(filenameBytes.length);
        this.sendData.putInt(parametersBytes.length);
        this.sendData.put(filenameBytes);
        this.sendData.put(parametersBytes);
        sendPacket(7946);
    }

    public void killProcess(final String processName) {
        addAction(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$killProcess$1(processName);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$killProcess$1(String processName) {
        this.sendData.rewind();
        this.sendData.put((byte) 3);
        byte[] bytes = processName.getBytes();
        this.sendData.putInt(bytes.length);
        this.sendData.put(bytes);
        sendPacket(7946);
    }

    public void listProcesses() {
        addAction(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$listProcesses$2();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$listProcesses$2() {
        this.sendData.rewind();
        this.sendData.put((byte) 4);
        this.sendData.putInt(0);
        if (!sendPacket(7946) && this.onGetProcessInfoListener != null) {
            this.onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
        }
    }

    public void setProcessAffinity(final String processName, final int affinityMask) {
        addAction(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$setProcessAffinity$3(processName, affinityMask);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setProcessAffinity$3(String processName, int affinityMask) {
        byte[] bytes = processName.getBytes();
        this.sendData.rewind();
        this.sendData.put((byte) 6);
        this.sendData.putInt(bytes.length + 9);
        this.sendData.putInt(0);
        this.sendData.putInt(affinityMask);
        this.sendData.put((byte) bytes.length);
        this.sendData.put(bytes);
        sendPacket(7946);
    }

    public void setProcessAffinity(final int pid, final int affinityMask) {
        addAction(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$setProcessAffinity$4(pid, affinityMask);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setProcessAffinity$4(int pid, int affinityMask) {
        this.sendData.rewind();
        this.sendData.put((byte) 6);
        this.sendData.putInt(9);
        this.sendData.putInt(pid);
        this.sendData.putInt(affinityMask);
        this.sendData.put((byte) 0);
        sendPacket(7946);
    }

    public void mouseEvent(final int flags, final int dx, final int dy, final int wheelDelta) {
        if (!this.initReceived) {
            return;
        }
        addAction(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$mouseEvent$5(flags, dx, dy, wheelDelta);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$mouseEvent$5(int flags, int dx, int dy, int wheelDelta) {
        this.sendData.rewind();
        this.sendData.put((byte) 7);
        this.sendData.putInt(10);
        this.sendData.putInt(flags);
        this.sendData.putShort((short) dx);
        this.sendData.putShort((short) dy);
        this.sendData.putShort((short) wheelDelta);
        this.sendData.put((byte) ((flags & 1) != 0 ? 1 : 0));
        sendPacket(7946);
    }

    public void keyboardEvent(final byte vkey, final int flags) {
        if (!this.initReceived) {
            return;
        }
        addAction(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$keyboardEvent$6(vkey, flags);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$keyboardEvent$6(byte vkey, int flags) {
        this.sendData.rewind();
        this.sendData.put((byte) 11);
        this.sendData.put(vkey);
        this.sendData.putInt(flags);
        sendPacket(7946);
    }

    public void bringToFront(String processName) {
        bringToFront(processName, 0L);
    }

    public void bringToFront(final String processName, final long handle) {
        addAction(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$bringToFront$7(processName, handle);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$bringToFront$7(String processName, long handle) {
        this.sendData.rewind();
        try {
            this.sendData.put((byte) 12);
            byte[] bytes = processName.getBytes();
            this.sendData.putInt(bytes.length);
            this.sendData.put(bytes);
            this.sendData.putLong(handle);
        } catch (BufferOverflowException e) {
            e.printStackTrace();
            this.sendData.rewind();
        }
        sendPacket(7946);
    }

    private void addAction(Runnable action) {
        synchronized (this.actions) {
            this.actions.add(action);
            this.actions.notify();
        }
    }

    public OnGetProcessInfoListener getOnGetProcessInfoListener() {
        return this.onGetProcessInfoListener;
    }

    public void setOnGetProcessInfoListener(OnGetProcessInfoListener onGetProcessInfoListener) {
        synchronized (this.actions) {
            this.onGetProcessInfoListener = onGetProcessInfoListener;
        }
    }

    private void startSendThread() {
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda10
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$startSendThread$8();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startSendThread$8() {
        while (this.running) {
            synchronized (this.actions) {
                while (this.initReceived && !this.actions.isEmpty()) {
                    this.actions.poll().run();
                }
                try {
                    this.actions.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    public void stop() {
        this.running = false;
        closeFakeInputWriter();
        if (this.socket != null) {
            this.socket.close();
            this.socket = null;
        }
        synchronized (this.actions) {
            this.actions.notify();
        }
    }

    private void handleRequest(byte requestCode, int port) {
        switch (requestCode) {
            case 1:
                this.initReceived = true;
                this.preferences = PreferenceManager.getDefaultSharedPreferences(this.activity.getBaseContext());
                if (!this.xinputDisabledInitialized) {
                    this.xinputDisabled = this.preferences.getBoolean("xinput_toggle", false);
                }
                synchronized (this.actions) {
                    this.actions.notify();
                }
                return;
            case 5:
                if (this.onGetProcessInfoListener != null) {
                    this.receiveData.position(this.receiveData.position() + 4);
                    int numProcesses = this.receiveData.getShort();
                    int index = this.receiveData.getShort();
                    int pid = this.receiveData.getInt();
                    long memoryUsage = this.receiveData.getLong();
                    int affinityMask = this.receiveData.getInt();
                    boolean wow64Process = this.receiveData.get() == 1;
                    byte[] bytes = new byte[32];
                    this.receiveData.get(bytes);
                    String name = StringUtils.fromANSIString(bytes);
                    this.onGetProcessInfoListener.onGetProcessInfo(index, numProcesses, new ProcessInfo(pid, name, memoryUsage, affinityMask, wow64Process));
                    return;
                }
                return;
            case 8:
            case 9:
            default:
                return;
            case 10:
            case 13:
                short x = this.receiveData.getShort();
                short y = this.receiveData.getShort();
                XServer xServer = this.activity.getXServer();
                xServer.pointer.setX(x);
                xServer.pointer.setY(y);
                this.activity.getXServerView().requestRender();
                return;
        }
    }

    public void start() {
        try {
            this.localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            try {
                this.localhost = InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException e2) {
            }
        }
        this.running = true;
        startSendThread();
        Executors.newSingleThreadExecutor().execute(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$start$9();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$start$9() {
        try {
            this.socket = new DatagramSocket((SocketAddress) null);
            this.socket.setReuseAddress(true);
            this.socket.bind(new InetSocketAddress((InetAddress) null, 7947));
            while (this.running) {
                this.socket.receive(this.receivePacket);
                synchronized (this.actions) {
                    this.receiveData.rewind();
                    byte requestCode = this.receiveData.get();
                    handleRequest(requestCode, this.receivePacket.getPort());
                }
            }
        } catch (IOException e) {
        }
    }

    public void sendGamepadState() {
        ControlsProfile profile = this.activity.getInputControlsView().getProfile();
        if (profile == null) {
            return;
        }
        GamepadState gamepadState = profile.getGamepadState();
        boolean useVirtualGamepad = profile.isVirtualGamepad() && this.activity.getInputControlsView().isShowTouchscreenControls();
        if (useVirtualGamepad) {
            int slot = assignSlot(-1);
            if (slot >= 0 && this.writers[slot] != null) {
                this.writers[slot].writeGamepadState(gamepadState);
                return;
            }
            return;
        }
        releaseSlot(-1);
    }

    public void sendGamepadState(ExternalController controller) {
        ExternalController profileController;
        if (controller == null) {
            return;
        }
        ControlsProfile profile = this.activity.getInputControlsView().getProfile();
        if (profile != null && (profileController = profile.getController(controller.getDeviceId())) != null && profileController.getControllerBindingCount() > 0) {
            int slot = assignSlot(controller.getDeviceId());
            if (slot >= 0 && this.writers[slot] != null) {
                this.writers[slot].writeGamepadState(controller.remappedState);
                return;
            }
            return;
        }
        int slot2 = assignSlot(controller.getDeviceId());
        if (slot2 >= 0 && this.writers[slot2] != null) {
            this.writers[slot2].writeGamepadState(controller.state);
        }
    }

    private int assignSlot(int deviceId) {
        Integer existing = this.deviceToSlot.get(Integer.valueOf(deviceId));
        if (existing != null) {
            return existing.intValue();
        }
        for (int slot = 0; slot < 4; slot++) {
            if (!this.usedSlots.contains(Integer.valueOf(slot))) {
                this.usedSlots.add(Integer.valueOf(slot));
                this.deviceToSlot.put(Integer.valueOf(deviceId), Integer.valueOf(slot));
                if (this.fakeInputBasePath != null && this.writers[slot] == null) {
                    this.writers[slot] = new FakeInputWriter(this.fakeInputBasePath, slot);
                    this.writers[slot].open();
                    Log.d("WinHandler", "Assigned device " + deviceId + " to slot " + slot);
                }
                return slot;
            }
        }
        Log.w("WinHandler", "No slots available for device " + deviceId);
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void releaseSlot(int deviceId) {
        Integer slot = this.deviceToSlot.remove(Integer.valueOf(deviceId));
        if (slot != null) {
            if (this.writers[slot.intValue()] != null) {
                this.writers[slot.intValue()].softRelease();
            }
            this.usedSlots.remove(slot);
            this.controllers.remove(Integer.valueOf(deviceId));
            Log.d("WinHandler", "Device " + deviceId + " disconnected (or OSC disabled). Slot soft-released: " + slot);
        }
    }

    public void setXInputDisabled(boolean disabled) {
        this.xinputDisabled = disabled;
        this.xinputDisabledInitialized = true;
        Log.d("WinHandler", "XInput Disabled set to: " + this.xinputDisabled);
    }

    public void setFakeInputPath(String fakeInputPath) {
        if (fakeInputPath != null && !fakeInputPath.isEmpty()) {
            this.fakeInputBasePath = fakeInputPath;
            Log.d("WinHandler", "FakeInputWriter base path set: " + fakeInputPath);
        }
    }

    public void closeFakeInputWriter() {
        if (this.inputManager != null && this.inputDeviceListener != null) {
            this.inputManager.unregisterInputDeviceListener(this.inputDeviceListener);
        }
        for (int i = 0; i < 4; i++) {
            if (this.writers[i] != null) {
                this.writers[i].destroy();
                this.writers[i] = null;
            }
        }
        this.deviceToSlot.clear();
        this.usedSlots.clear();
        this.controllers.clear();
    }

    private ExternalController getController(int deviceId) {
        if (this.controllers.containsKey(Integer.valueOf(deviceId))) {
            return this.controllers.get(Integer.valueOf(deviceId));
        }
        ExternalController controller = ExternalController.getController(deviceId);
        if (controller != null) {
            this.controllers.put(Integer.valueOf(deviceId), controller);
        }
        return controller;
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean handled = false;
        ExternalController controller = getController(event.getDeviceId());
        if (controller != null && (handled = controller.updateStateFromMotionEvent(event))) {
            sendGamepadState(controller);
        }
        return handled;
    }

    public boolean onKeyEvent(KeyEvent event) {
        boolean handled = false;
        ExternalController controller = getController(event.getDeviceId());
        if (controller != null && event.getRepeatCount() == 0) {
            int action = event.getAction();
            if (action == 0) {
                handled = controller.updateStateFromKeyEvent(event);
            } else if (action == 1) {
                handled = controller.updateStateFromKeyEvent(event);
            }
            if (handled) {
                sendGamepadState(controller);
            }
        }
        return handled;
    }

    public byte getInputType() {
        return this.inputType;
    }

    public void setInputType(byte inputType) {
        this.inputType = inputType;
    }

    public void execWithDelay(final String command, int delaySeconds) {
        if (command == null || command.trim().isEmpty() || delaySeconds < 0) {
            return;
        }
        Executors.newSingleThreadScheduledExecutor().schedule(new Runnable() { // from class: com.winlator.cmod.winhandler.WinHandler$$ExternalSyntheticLambda9
            @Override // java.lang.Runnable
            public final void run() {
                WinHandler.this.lambda$execWithDelay$10(command);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }
}
