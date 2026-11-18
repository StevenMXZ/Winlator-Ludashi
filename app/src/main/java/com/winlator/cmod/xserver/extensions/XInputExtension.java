package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadRequest;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XIBadDevice;
import com.winlator.cmod.xserver.errors.XInputError;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.XIRawEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/*
 * Minimal X Input Extension (XI 2.2) for Wine 10+.
 * Wine takes raw input (WM_INPUT / DirectInput) only from XI2 raw events selected on the root window,
 * so without this extension games that read relative mouse movement get nothing.
 *
 * Devices: 2 = Virtual core pointer, 3 = Virtual core keyboard,
 *          4 = slave pointer (2 relative valuators, 7 buttons), the sourceid of every raw event.
 * Version 2.2 is reported because libXi drops sourceid below 2.2 and Wine ignores raw buttons with sourceid 0.
 */
public class XInputExtension implements Extension, Pointer.OnPointerMotionListener, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -110;
    // libXi registers 17 XI 1.x wire handlers from first_event, keep them clear of MIT-SHM (64) and RANDR (65-66)
    private static final byte FIRST_EVENT_ID = 67;
    private static final short SERVER_MAJOR_VERSION = 2;
    private static final short SERVER_MINOR_VERSION = 2;

    public static final short DEVICE_ALL = 0;
    public static final short DEVICE_ALL_MASTER = 1;
    public static final short DEVICE_MASTER_POINTER = 2;
    public static final short DEVICE_MASTER_KEYBOARD = 3;
    public static final short DEVICE_SLAVE_POINTER = 4;

    public static final short XI_RAW_BUTTON_PRESS = 15;
    public static final short XI_RAW_BUTTON_RELEASE = 16;
    public static final short XI_RAW_MOTION = 17;

    private static final short USE_MASTER_POINTER = 1;
    private static final short USE_MASTER_KEYBOARD = 2;
    private static final short USE_SLAVE_POINTER = 3;
    private static final short CLASS_BUTTON = 1;
    private static final short CLASS_VALUATOR = 2;
    private static final byte MODE_RELATIVE = 0;
    private static final int NUM_BUTTONS = Pointer.MAX_BUTTONS;
    private static final int VALUATOR_CLASS_LENGTH = 44;

    private static abstract class ClientOpcodes {
        private static final byte GET_EXTENSION_VERSION = 1;
        private static final byte LIST_INPUT_DEVICES = 2;
        private static final byte OPEN_DEVICE = 3;
        private static final byte CLOSE_DEVICE = 4;
        private static final byte SELECT_EXTENSION_EVENT = 6;
        private static final byte GET_DEVICE_BUTTON_MAPPING = 28;
        private static final byte XI_WARP_POINTER = 41;
        private static final byte XI_CHANGE_CURSOR = 42;
        private static final byte XI_CHANGE_HIERARCHY = 43;
        private static final byte XI_SET_CLIENT_POINTER = 44;
        private static final byte XI_GET_CLIENT_POINTER = 45;
        private static final byte XI_SELECT_EVENTS = 46;
        private static final byte XI_QUERY_VERSION = 47;
        private static final byte XI_QUERY_DEVICE = 48;
        private static final byte XI_SET_FOCUS = 49;
        private static final byte XI_UNGRAB_DEVICE = 52;
        private static final byte XI_ALLOW_EVENTS = 53;
        private static final byte XI_CHANGE_PROPERTY = 57;
        private static final byte XI_DELETE_PROPERTY = 58;
        private static final byte XI_BARRIER_RELEASE_POINTER = 61;
    }

    private static class DeviceInfo {
        private final short id;
        private final short use;
        private final short attachment;
        private final String name;
        private final boolean hasPointerClasses;

        private DeviceInfo(short id, short use, short attachment, String name, boolean hasPointerClasses) {
            this.id = id;
            this.use = use;
            this.attachment = attachment;
            this.name = name;
            this.hasPointerClasses = hasPointerClasses;
        }
    }

    private static final DeviceInfo[] devices = {
        new DeviceInfo(DEVICE_MASTER_POINTER, USE_MASTER_POINTER, DEVICE_MASTER_KEYBOARD, "Virtual core pointer", true),
        new DeviceInfo(DEVICE_MASTER_KEYBOARD, USE_MASTER_KEYBOARD, DEVICE_MASTER_POINTER, "Virtual core keyboard", false),
        new DeviceInfo(DEVICE_SLAVE_POINTER, USE_SLAVE_POINTER, DEVICE_MASTER_POINTER, "Winlator pointer", true)
    };

    private final XServer xServer;
    // client -> window id -> device id -> XI event mask
    private final HashMap<XClient, HashMap<Integer, HashMap<Integer, Long>>> selections = new HashMap<>();

    public XInputExtension(XServer xServer) {
        this.xServer = xServer;
        xServer.windowManager.addOnResourceLifecycleListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    @Override
    public String getName() {
        return "XInputExtension";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return XInputError.BASE_ERROR_CODE;
    }

    @Override
    public byte getFirstEventId() {
        return FIRST_EVENT_ID;
    }

    // Relative mouse movement goes through WinHandler, raw events would move the cursor twice
    private boolean isRawInputActive() {
        return !xServer.isRelativeMouseMovement() && !xServer.isMouseDisabled();
    }

    public void sendRawMotion(int dx, int dy) {
        if ((dx == 0 && dy == 0) || !isRawInputActive()) return;
        deliver(XIRawEvent.motion((int)System.currentTimeMillis(), dx, dy));
    }

    @Override
    public void onPointerButtonPress(Pointer.Button button) {
        if (isRawInputActive()) deliver(XIRawEvent.button((int)System.currentTimeMillis(), button.code(), true));
    }

    @Override
    public void onPointerButtonRelease(Pointer.Button button) {
        if (isRawInputActive()) deliver(XIRawEvent.button((int)System.currentTimeMillis(), button.code(), false));
    }

    // Raw events go only to root window selections for XIAllDevices, XIAllMasterDevices or the master pointer
    private void deliver(XIRawEvent event) {
        int rootId = xServer.windowManager.rootWindow.id;
        long bit = 1L << event.getEvtype();
        ArrayList<XClient> clients = new ArrayList<>();

        synchronized (selections) {
            for (Map.Entry<XClient, HashMap<Integer, HashMap<Integer, Long>>> entry : selections.entrySet()) {
                HashMap<Integer, Long> masks = entry.getValue().get(rootId);
                if (masks == null) continue;
                long mask = getMask(masks, DEVICE_ALL) | getMask(masks, DEVICE_ALL_MASTER) | getMask(masks, DEVICE_MASTER_POINTER);
                if ((mask & bit) != 0) clients.add(entry.getKey());
            }
        }

        for (XClient client : clients) client.sendEvent(event);
    }

    private static long getMask(HashMap<Integer, Long> masks, int deviceId) {
        Long mask = masks.get(deviceId);
        return mask != null ? mask : 0;
    }

    public void removeClient(XClient client) {
        synchronized (selections) {
            selections.remove(client);
        }
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (!(resource instanceof Window)) return;
        synchronized (selections) {
            Iterator<HashMap<Integer, HashMap<Integer, Long>>> iterator = selections.values().iterator();
            while (iterator.hasNext()) {
                HashMap<Integer, HashMap<Integer, Long>> windows = iterator.next();
                windows.remove(resource.id);
                if (windows.isEmpty()) iterator.remove();
            }
        }
    }

    private void getExtensionVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        client.skipRequest();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(ClientOpcodes.GET_EXTENSION_VERSION);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort(SERVER_MAJOR_VERSION);
            outputStream.writeShort(SERVER_MINOR_VERSION);
            outputStream.writeByte((byte)1);
            outputStream.writePad(19);
        }
    }

    // Used by Wine's wintab to look for tablets: report none
    private void listInputDevices(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        client.skipRequest();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(ClientOpcodes.LIST_INPUT_DEVICES);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte((byte)0);
            outputStream.writePad(23);
        }
    }

    private void openDevice(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int deviceId = inputStream.readUnsignedByte();
        inputStream.skip(3);
        if (deviceId != DEVICE_SLAVE_POINTER) throw new XIBadDevice(deviceId);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(ClientOpcodes.OPEN_DEVICE);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte((byte)0);
            outputStream.writePad(23);
        }
    }

    private void getDeviceButtonMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int deviceId = inputStream.readUnsignedByte();
        inputStream.skip(3);
        if (deviceId != DEVICE_SLAVE_POINTER) throw new XIBadDevice(deviceId);

        int mapLength = (NUM_BUTTONS + 3) & ~3;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(ClientOpcodes.GET_DEVICE_BUTTON_MAPPING);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(mapLength / 4);
            outputStream.writeByte((byte)NUM_BUTTONS);
            outputStream.writePad(23);
            for (int i = 1; i <= NUM_BUTTONS; i++) outputStream.writeByte((byte)i);
            outputStream.writePad(mapLength - NUM_BUTTONS);
        }
    }

    private void xiQueryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int major = inputStream.readUnsignedShort();
        int minor = inputStream.readUnsignedShort();

        if (major > SERVER_MAJOR_VERSION || (major == SERVER_MAJOR_VERSION && minor > SERVER_MINOR_VERSION)) {
            major = SERVER_MAJOR_VERSION;
            minor = SERVER_MINOR_VERSION;
        }

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(ClientOpcodes.XI_QUERY_VERSION);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)major);
            outputStream.writeShort((short)minor);
            outputStream.writePad(20);
        }
    }

    private void xiGetClientPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(ClientOpcodes.XI_GET_CLIENT_POINTER);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte((byte)1);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(DEVICE_MASTER_POINTER);
            outputStream.writePad(20);
        }
    }

    private void xiSelectEvents(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int numMasks = inputStream.readUnsignedShort();
        inputStream.skip(2);

        HashMap<Integer, Long> newMasks = new HashMap<>();
        for (int i = 0; i < numMasks && client.getRemainingRequestLength() >= 4; i++) {
            int deviceId = inputStream.readUnsignedShort();
            int maskLength = inputStream.readUnsignedShort() * 4;
            if (maskLength > client.getRemainingRequestLength()) break;

            long mask = 0;
            for (int j = 0; j < maskLength; j++) {
                long value = inputStream.readUnsignedByte();
                if (j < 8) mask |= value << (8 * j);
            }
            newMasks.put(deviceId, mask);
        }
        client.skipRequest();

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window = xServer.windowManager.getWindow(windowId);
            if (window == null) throw new BadWindow(windowId);

            synchronized (selections) {
                HashMap<Integer, HashMap<Integer, Long>> windows = selections.get(client);
                if (windows == null) {
                    windows = new HashMap<>();
                    selections.put(client, windows);
                }

                HashMap<Integer, Long> masks = windows.get(windowId);
                if (masks == null) {
                    masks = new HashMap<>();
                    windows.put(windowId, masks);
                }

                for (Map.Entry<Integer, Long> entry : newMasks.entrySet()) {
                    if (entry.getValue() == 0) masks.remove(entry.getKey());
                    else masks.put(entry.getKey(), entry.getValue());
                }

                if (masks.isEmpty()) windows.remove(windowId);
                if (windows.isEmpty()) selections.remove(client);
            }
        }
    }

    private void xiQueryDevice(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int deviceId = inputStream.readUnsignedShort();
        inputStream.skip(2);

        ArrayList<DeviceInfo> result = new ArrayList<>();
        for (DeviceInfo device : devices) {
            boolean isMaster = device.use == USE_MASTER_POINTER || device.use == USE_MASTER_KEYBOARD;
            if (deviceId == DEVICE_ALL || device.id == deviceId || (deviceId == DEVICE_ALL_MASTER && isMaster)) result.add(device);
        }
        if (result.isEmpty()) throw new XIBadDevice(deviceId);

        int length = 0;
        for (DeviceInfo device : result) length += getDeviceInfoLength(device);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(ClientOpcodes.XI_QUERY_DEVICE);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(length / 4);
            outputStream.writeShort((short)result.size());
            outputStream.writePad(22);

            for (DeviceInfo device : result) writeDeviceInfo(outputStream, device);
        }
    }

    private static int getButtonMaskLength() {
        return ((NUM_BUTTONS + 31) / 32) * 4;
    }

    private static int getButtonClassLength() {
        return 8 + getButtonMaskLength() + 4 * NUM_BUTTONS;
    }

    private static int getDeviceInfoLength(DeviceInfo device) {
        int length = 12 + ((device.name.length() + 3) & ~3);
        if (device.hasPointerClasses) length += getButtonClassLength() + 2 * VALUATOR_CLASS_LENGTH;
        return length;
    }

    private static void writeDeviceInfo(XOutputStream outputStream, DeviceInfo device) {
        outputStream.writeShort(device.id);
        outputStream.writeShort(device.use);
        outputStream.writeShort(device.attachment);
        outputStream.writeShort((short)(device.hasPointerClasses ? 3 : 0));
        outputStream.writeShort((short)device.name.length());
        outputStream.writeByte((byte)1);
        outputStream.writeByte((byte)0);
        outputStream.writeString8(device.name);

        if (!device.hasPointerClasses) return;

        // XIButtonClass: all buttons released, no labels
        outputStream.writeShort(CLASS_BUTTON);
        outputStream.writeShort((short)(getButtonClassLength() / 4));
        outputStream.writeShort(DEVICE_SLAVE_POINTER);
        outputStream.writeShort((short)NUM_BUTTONS);
        outputStream.writePad(getButtonMaskLength());
        outputStream.writePad(4 * NUM_BUTTONS);

        // XIValuatorClass X (0) and Y (1): relative, min == max so Wine uses 1:1 scale
        for (short number = 0; number < 2; number++) {
            outputStream.writeShort(CLASS_VALUATOR);
            outputStream.writeShort((short)(VALUATOR_CLASS_LENGTH / 4));
            outputStream.writeShort(DEVICE_SLAVE_POINTER);
            outputStream.writeShort(number);
            outputStream.writeInt(0);
            outputStream.writeFP3232(0);
            outputStream.writeFP3232(0);
            outputStream.writeFP3232(0);
            outputStream.writeInt(1);
            outputStream.writeByte(MODE_RELATIVE);
            outputStream.writePad(3);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.GET_EXTENSION_VERSION:
                getExtensionVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.LIST_INPUT_DEVICES:
                listInputDevices(client, inputStream, outputStream);
                break;
            case ClientOpcodes.OPEN_DEVICE:
                openDevice(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_DEVICE_BUTTON_MAPPING:
                getDeviceButtonMapping(client, inputStream, outputStream);
                break;
            case ClientOpcodes.XI_QUERY_VERSION:
                xiQueryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.XI_GET_CLIENT_POINTER:
                xiGetClientPointer(client, inputStream, outputStream);
                break;
            case ClientOpcodes.XI_SELECT_EVENTS:
                xiSelectEvents(client, inputStream, outputStream);
                break;
            case ClientOpcodes.XI_QUERY_DEVICE:
                xiQueryDevice(client, inputStream, outputStream);
                break;
            case ClientOpcodes.CLOSE_DEVICE:
            case ClientOpcodes.SELECT_EXTENSION_EVENT:
            case ClientOpcodes.XI_WARP_POINTER:
            case ClientOpcodes.XI_CHANGE_CURSOR:
            case ClientOpcodes.XI_CHANGE_HIERARCHY:
            case ClientOpcodes.XI_SET_CLIENT_POINTER:
            case ClientOpcodes.XI_SET_FOCUS:
            case ClientOpcodes.XI_UNGRAB_DEVICE:
            case ClientOpcodes.XI_ALLOW_EVENTS:
            case ClientOpcodes.XI_CHANGE_PROPERTY:
            case ClientOpcodes.XI_DELETE_PROPERTY:
            case ClientOpcodes.XI_BARRIER_RELEASE_POINTER:
                client.skipRequest();
                break;
            default:
                // may expect a reply, answer with an error so the client never hangs
                throw new BadRequest();
        }
    }
}
