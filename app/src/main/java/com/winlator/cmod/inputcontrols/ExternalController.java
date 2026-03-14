package com.winlator.cmod.inputcontrols;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.core.view.InputDeviceCompat;
import androidx.exifinterface.media.ExifInterface;
import com.winlator.cmod.XServerDisplayActivity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes14.dex */
public class ExternalController {
    public static final byte IDX_BUTTON_A = 0;
    public static final byte IDX_BUTTON_B = 1;
    public static final byte IDX_BUTTON_L1 = 4;
    public static final byte IDX_BUTTON_L2 = 10;
    public static final byte IDX_BUTTON_L3 = 8;
    public static final byte IDX_BUTTON_R1 = 5;
    public static final byte IDX_BUTTON_R2 = 11;
    public static final byte IDX_BUTTON_R3 = 9;
    public static final byte IDX_BUTTON_SELECT = 6;
    public static final byte IDX_BUTTON_START = 7;
    public static final byte IDX_BUTTON_X = 2;
    public static final byte IDX_BUTTON_Y = 3;
    public static final HashMap<Byte, Byte> buttonMappings = new HashMap<>();
    private XServerDisplayActivity activity;
    private String id;
    private String name;
    private int deviceId = -1;
    private final ArrayList<ExternalControllerBinding> controllerBindings = new ArrayList<>();
    public final GamepadState state = new GamepadState();
    public final GamepadState remappedState = new GamepadState();
    private boolean triggerLPressedViaButton = false;
    private boolean triggerRPressedViaButton = false;

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getDeviceId() {
        if (this.deviceId == -1) {
            int[] deviceIds = InputDevice.getDeviceIds();
            int length = deviceIds.length;
            int i = 0;
            while (true) {
                if (i < length) {
                    int deviceId = deviceIds[i];
                    InputDevice device = InputDevice.getDevice(deviceId);
                    if (device == null || !device.getDescriptor().equals(this.id)) {
                        i++;
                    } else {
                        this.deviceId = deviceId;
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        return this.deviceId;
    }

    public boolean isConnected() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && device.getDescriptor().equals(this.id)) {
                return true;
            }
        }
        return false;
    }

    public ExternalControllerBinding getControllerBinding(int keyCode) {
        Iterator<ExternalControllerBinding> it = this.controllerBindings.iterator();
        while (it.hasNext()) {
            ExternalControllerBinding controllerBinding = it.next();
            if (controllerBinding.getKeyCode() == keyCode) {
                return controllerBinding;
            }
        }
        return null;
    }

    public ExternalControllerBinding getControllerBindingAt(int index) {
        return this.controllerBindings.get(index);
    }

    public void addControllerBinding(ExternalControllerBinding controllerBinding) {
        if (getControllerBinding(controllerBinding.getKeyCode()) == null) {
            this.controllerBindings.add(controllerBinding);
        }
    }

    public int getPosition(ExternalControllerBinding controllerBinding) {
        return this.controllerBindings.indexOf(controllerBinding);
    }

    public void removeControllerBinding(ExternalControllerBinding controllerBinding) {
        this.controllerBindings.remove(controllerBinding);
    }

    public void setButtonMapping(byte originalButton, byte mappedButton) {
        buttonMappings.put(Byte.valueOf(originalButton), Byte.valueOf(mappedButton));
    }

    public byte getMappedButton(byte originalButton) {
        byte mappedButton = buttonMappings.getOrDefault(Byte.valueOf(originalButton), Byte.valueOf(originalButton)).byteValue();
        return mappedButton;
    }

    public int getControllerBindingCount() {
        return this.controllerBindings.size();
    }

    public JSONObject toJSONObject() {
        try {
            if (this.controllerBindings.isEmpty()) {
                return null;
            }
            JSONObject controllerJSONObject = new JSONObject();
            controllerJSONObject.put("id", this.id);
            controllerJSONObject.put("name", this.name);
            JSONArray controllerBindingsJSONArray = new JSONArray();
            Iterator<ExternalControllerBinding> it = this.controllerBindings.iterator();
            while (it.hasNext()) {
                ExternalControllerBinding controllerBinding = it.next();
                controllerBindingsJSONArray.put(controllerBinding.toJSONObject());
            }
            controllerJSONObject.put("controllerBindings", controllerBindingsJSONArray);
            return controllerJSONObject;
        } catch (JSONException e) {
            return null;
        }
    }

    public boolean equals(Object obj) {
        return obj instanceof ExternalController ? ((ExternalController) obj).id.equals(this.id) : super.equals(obj);
    }

    private void processJoystickInput(MotionEvent event, int historyPos) {
        boolean z = false;
        this.state.thumbLX = getCenteredAxis(event, 0, historyPos);
        this.state.thumbLY = getCenteredAxis(event, 1, historyPos);
        this.state.thumbRX = getCenteredAxis(event, 11, historyPos);
        this.state.thumbRY = getCenteredAxis(event, 14, historyPos);
        if (historyPos == -1) {
            float axisX = getCenteredAxis(event, 15, historyPos);
            float axisY = getCenteredAxis(event, 16, historyPos);
            this.state.dpad[0] = axisY == -1.0f && Math.abs(this.state.thumbLY) < 0.15f;
            this.state.dpad[1] = axisX == 1.0f && Math.abs(this.state.thumbLX) < 0.15f;
            this.state.dpad[2] = axisY == 1.0f && Math.abs(this.state.thumbLY) < 0.15f;
            boolean[] zArr = this.state.dpad;
            if (axisX == -1.0f && Math.abs(this.state.thumbLX) < 0.15f) {
                z = true;
            }
            zArr[3] = z;
        }
    }

    private void processTriggerButton(MotionEvent event) {
        float l = event.getAxisValue(event.getAxisValue(17) == 0.0f ? 23 : 17);
        float r = event.getAxisValue(event.getAxisValue(18) == 0.0f ? 22 : 18);
        this.state.triggerL = l;
        this.state.triggerR = r;
        this.state.setPressed(10, l == 1.0f);
        this.state.setPressed(11, r == 1.0f);
    }

    public boolean isXboxController() {
        InputDevice device = InputDevice.getDevice(getDeviceId());
        if (device == null) {
            return false;
        }
        int vendorId = device.getVendorId();
        return vendorId == 1118;
    }

    private void processXboxTriggerButton(MotionEvent event) {
        float l;
        float r;
        if (event.getAxisValue(17) == 0.0f) {
            l = event.getAxisValue(23);
        } else {
            l = event.getAxisValue(17);
        }
        if (event.getAxisValue(18) == 0.0f) {
            r = event.getAxisValue(22);
        } else {
            r = event.getAxisValue(18);
        }
        if (l > 0.0f) {
            this.state.triggerL = 1.0f;
            this.state.setPressed(10, true);
        } else {
            this.state.triggerL = 0.0f;
            this.state.setPressed(10, false);
        }
        if (r > 0.0f) {
            this.state.triggerR = 1.0f;
            this.state.setPressed(11, true);
        } else {
            this.state.triggerR = 0.0f;
            this.state.setPressed(11, false);
        }
    }

    public boolean updateStateFromMotionEvent(MotionEvent event) {
        if (isJoystickDevice(event)) {
            processTriggerButton(event);
            int historySize = event.getHistorySize();
            for (int i = 0; i < historySize; i++) {
                processJoystickInput(event, i);
            }
            processJoystickInput(event, -1);
            return true;
        }
        return false;
    }

    public boolean updateStateFromKeyEvent(KeyEvent event) {
        boolean z = false;
        boolean pressed = event.getAction() == 0;
        int keyCode = event.getKeyCode();
        int buttonIdx = getButtonIdxByKeyCode(keyCode);
        if (buttonIdx != -1) {
            if (buttonIdx == 10 || buttonIdx == 11) {
                return true;
            }
            this.state.setPressed(buttonIdx, pressed);
            return true;
        }
        switch (keyCode) {
            case 19:
                this.state.dpad[0] = pressed && Math.abs(this.state.thumbLY) < 0.15f;
                break;
            case 20:
                boolean[] zArr = this.state.dpad;
                if (pressed && Math.abs(this.state.thumbLY) < 0.15f) {
                    z = true;
                }
                zArr[2] = z;
                break;
            case 21:
                boolean[] zArr2 = this.state.dpad;
                if (pressed && Math.abs(this.state.thumbLX) < 0.15f) {
                    z = true;
                }
                zArr2[3] = z;
                break;
            case 22:
                boolean[] zArr3 = this.state.dpad;
                if (pressed && Math.abs(this.state.thumbLX) < 0.15f) {
                    z = true;
                }
                zArr3[1] = z;
                break;
        }
        return true;
    }

    public static ArrayList<ExternalController> getControllers() {
        int[] deviceIds = InputDevice.getDeviceIds();
        ArrayList<ExternalController> controllers = new ArrayList<>();
        for (int i = deviceIds.length - 1; i >= 0; i--) {
            InputDevice device = InputDevice.getDevice(deviceIds[i]);
            if (isGameController(device)) {
                ExternalController controller = new ExternalController();
                controller.setId(device.getDescriptor());
                controller.setName(device.getName());
                controllers.add(controller);
            }
        }
        return controllers;
    }

    public static ExternalController getController(String id) {
        Iterator<ExternalController> it = getControllers().iterator();
        while (it.hasNext()) {
            ExternalController controller = it.next();
            if (controller.getId().equals(id)) {
                return controller;
            }
        }
        return null;
    }

    public static ExternalController getController(int deviceId) {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int i = deviceIds.length - 1; i >= 0; i--) {
            if (deviceIds[i] == deviceId || deviceId == 0) {
                InputDevice device = InputDevice.getDevice(deviceIds[i]);
                if (isGameController(device)) {
                    ExternalController controller = new ExternalController();
                    controller.setId(device.getDescriptor());
                    controller.setName(device.getName());
                    controller.deviceId = deviceIds[i];
                    return controller;
                }
            }
        }
        return null;
    }

    public static boolean isGameController(InputDevice device) {
        if (device == null) {
            return false;
        }
        int sources = device.getSources();
        if (device.isVirtual()) {
            return false;
        }
        return (sources & InputDeviceCompat.SOURCE_GAMEPAD) == 1025 || ((sources & InputDeviceCompat.SOURCE_JOYSTICK) == 16777232 && (sources & 8194) == 0);
    }

    public float getCenteredAxis(MotionEvent event, int axis, int historyPos) {
        if (axis == 15 || axis == 16) {
            float value = event.getAxisValue(axis);
            if (Math.abs(value) == 1.0f) {
                return value;
            }
            return 0.0f;
        }
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range == null) {
            return 0.0f;
        }
        float flat = range.getFlat();
        float value2 = historyPos < 0 ? event.getAxisValue(axis) : event.getHistoricalAxisValue(axis, historyPos);
        if (Math.abs(value2) <= flat) {
            return 0.0f;
        }
        if ((axis == 0 || axis == 1 || axis == 11 || axis == 14) && Math.abs(value2) >= 0.15f) {
            return value2;
        }
        return 0.0f;
    }

    public static boolean isJoystickDevice(MotionEvent event) {
        return (event.getSource() & InputDeviceCompat.SOURCE_JOYSTICK) == 16777232 && event.getAction() == 2;
    }

    public static int getButtonIdxByKeyCode(int keyCode) {
        switch (keyCode) {
            case 96:
                return 0;
            case 97:
                return 1;
            case 98:
            case 101:
            default:
                return -1;
            case 99:
                return 2;
            case 100:
                return 3;
            case 102:
                return 4;
            case 103:
                return 5;
            case 104:
                return 10;
            case 105:
                return 11;
            case 106:
                return 8;
            case 107:
                return 9;
            case 108:
                return 7;
            case 109:
                return 6;
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public static int getButtonIdxByName(String name) {
        boolean z;
        switch (name.hashCode()) {
            case -1852692228:
                if (name.equals("SELECT")) {
                    z = 6;
                    break;
                }
                z = -1;
                break;
            case 65:
                if (name.equals(ExifInterface.GPS_MEASUREMENT_IN_PROGRESS)) {
                    z = false;
                    break;
                }
                z = -1;
                break;
            case 66:
                if (name.equals("B")) {
                    z = true;
                    break;
                }
                z = -1;
                break;
            case 88:
                if (name.equals("X")) {
                    z = 2;
                    break;
                }
                z = -1;
                break;
            case 89:
                if (name.equals("Y")) {
                    z = 3;
                    break;
                }
                z = -1;
                break;
            case 2405:
                if (name.equals("L1")) {
                    z = 4;
                    break;
                }
                z = -1;
                break;
            case 2406:
                if (name.equals("L2")) {
                    z = 10;
                    break;
                }
                z = -1;
                break;
            case 2407:
                if (name.equals("L3")) {
                    z = 8;
                    break;
                }
                z = -1;
                break;
            case 2591:
                if (name.equals("R1")) {
                    z = 5;
                    break;
                }
                z = -1;
                break;
            case 2592:
                if (name.equals("R2")) {
                    z = 11;
                    break;
                }
                z = -1;
                break;
            case 2593:
                if (name.equals("R3")) {
                    z = 9;
                    break;
                }
                z = -1;
                break;
            case 79219778:
                if (name.equals("START")) {
                    z = 7;
                    break;
                }
                z = -1;
                break;
            default:
                z = -1;
                break;
        }
        switch (z) {
            case false:
                return 0;
            case true:
                return 1;
            case true:
                return 2;
            case true:
                return 3;
            case true:
                return 4;
            case true:
                return 5;
            case true:
                return 6;
            case true:
                return 7;
            case true:
                return 8;
            case true:
                return 9;
            case true:
                return 10;
            case true:
                return 11;
            default:
                return -1;
        }
    }
}
