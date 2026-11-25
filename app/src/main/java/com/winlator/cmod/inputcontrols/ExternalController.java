package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.XServerDisplayActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

public class ExternalController {
    public static final byte IDX_BUTTON_A = 0;
    public static final byte IDX_BUTTON_B = 1;
    public static final byte IDX_BUTTON_X = 2;
    public static final byte IDX_BUTTON_Y = 3;
    public static final byte IDX_BUTTON_L1 = 4;
    public static final byte IDX_BUTTON_R1 = 5;
    public static final byte IDX_BUTTON_SELECT = 6;
    public static final byte IDX_BUTTON_START = 7;
    public static final byte IDX_BUTTON_L3 = 8;
    public static final byte IDX_BUTTON_R3 = 9;
    public static final byte IDX_BUTTON_L2 = 10;
    public static final byte IDX_BUTTON_R2 = 11;
    private String name;
    private String id;
    private int deviceId = -1;
    private final ArrayList<ExternalControllerBinding> controllerBindings = new ArrayList<>();
    public final GamepadState state = new GamepadState();
    public final GamepadState remappedState = new GamepadState();
    private XServerDisplayActivity activity;



    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }








    // Remove static keyword
    public static final HashMap<Byte, Byte> buttonMappings = new HashMap<>();


    private boolean triggerLPressedViaButton = false;
    private boolean triggerRPressedViaButton = false;


//    public ExternalController() {
//
//
//        // Initialize trigger mappings to themselves
//        buttonMappings.put(IDX_BUTTON_L2, IDX_BUTTON_L2);
//        buttonMappings.put(IDX_BUTTON_R2, IDX_BUTTON_R2);
//
//        // Ensure triggerType is set to TRIGGER_IS_AXIS
//        triggerType = TRIGGER_IS_AXIS;
//    }


    public int getDeviceId() {
        if (this.deviceId == -1) {
            for (int deviceId : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (isGameController(device) && device.getDescriptor().equals(id)) {
                    this.deviceId = deviceId;
                    break;
                }
            }
        }
        return this.deviceId;
    }

    public boolean isConnected() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (isGameController(device) && device.getDescriptor().equals(id)) return true;
        }
        return false;
    }

    public ExternalControllerBinding getControllerBinding(int keyCode) {
        for (ExternalControllerBinding controllerBinding : controllerBindings) {
            if (controllerBinding.getKeyCode() == keyCode) return controllerBinding;
        }
        return null;
    }

    public ExternalControllerBinding getControllerBindingAt(int index) {
        return controllerBindings.get(index);
    }

    public void addControllerBinding(ExternalControllerBinding controllerBinding) {
        if (getControllerBinding(controllerBinding.getKeyCode()) == null) controllerBindings.add(controllerBinding);
    }

    public int getPosition(ExternalControllerBinding controllerBinding) {
        return controllerBindings.indexOf(controllerBinding);
    }

    public void removeControllerBinding(ExternalControllerBinding controllerBinding) {
        controllerBindings.remove(controllerBinding);
    }

    public void setButtonMapping(byte originalButton, byte mappedButton) {
        buttonMappings.put(originalButton, mappedButton);
        // Remove triggerType handling from here
    }


    public byte getMappedButton(byte originalButton) {
        byte mappedButton = buttonMappings.getOrDefault(originalButton, originalButton);
//        Log.d("ExternalController", "getMappedButton: Original button = " + originalButton + ", Mapped button = " + mappedButton);
        return mappedButton;
    }


    public int getControllerBindingCount() {
        return controllerBindings.size();
    }

    public JSONObject toJSONObject() {
        try {
            if (controllerBindings.isEmpty()) return null;
            JSONObject controllerJSONObject = new JSONObject();
            controllerJSONObject.put("id", id);
            controllerJSONObject.put("name", name);

            JSONArray controllerBindingsJSONArray = new JSONArray();
            for (ExternalControllerBinding controllerBinding : controllerBindings) controllerBindingsJSONArray.put(controllerBinding.toJSONObject());
            controllerJSONObject.put("controllerBindings", controllerBindingsJSONArray);

            return controllerJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof ExternalController ? ((ExternalController)obj).id.equals(this.id) : super.equals(obj);
    }

    private void processJoystickInput(MotionEvent event, int historyPos) {
        state.thumbLX = getCenteredAxis(event, MotionEvent.AXIS_X, historyPos);
        state.thumbLY = getCenteredAxis(event, MotionEvent.AXIS_Y, historyPos);
        state.thumbRX = getCenteredAxis(event, MotionEvent.AXIS_Z, historyPos);
        state.thumbRY = getCenteredAxis(event, MotionEvent.AXIS_RZ, historyPos);

        if (historyPos == -1) {
            float axisX = getCenteredAxis(event, MotionEvent.AXIS_HAT_X, historyPos);
            float axisY = getCenteredAxis(event, MotionEvent.AXIS_HAT_Y, historyPos);

            state.dpad[0] = axisY == -1.0f && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
            state.dpad[1] = axisX == 1.0f && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
            state.dpad[2] = axisY == 1.0f && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
            state.dpad[3] = axisX == -1.0f && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
        }
    }



    private void processTriggerButton(MotionEvent event) {
        float l = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) == 0f ? event.getAxisValue(MotionEvent.AXIS_BRAKE) : event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float r = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) == 0f ? event.getAxisValue(MotionEvent.AXIS_GAS) : event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        state.triggerL = l;
        state.triggerR = r;
        state.setPressed(IDX_BUTTON_L2, l == 1.0f);
        state.setPressed(IDX_BUTTON_R2, r == 1.0f);
    }

    public boolean isXboxController() {
        InputDevice device = InputDevice.getDevice(getDeviceId());
        if (device == null) return false;
        int vendorId = device.getVendorId();
        return vendorId == 0x045E; // Microsoft's Vendor ID for Xbox controllers
    }

    private void processXboxTriggerButton(MotionEvent event) {
        // Retrieve axis values for triggers
        float l = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) == 0f
                ? event.getAxisValue(MotionEvent.AXIS_BRAKE)
                : event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float r = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) == 0f
                ? event.getAxisValue(MotionEvent.AXIS_GAS)
                : event.getAxisValue(MotionEvent.AXIS_RTRIGGER);

        // Simulate full press by setting trigger values to 1.0f when pulled
        if (l > 0.0f) {
            state.triggerL = 1.0f; // Simulate full press
            state.setPressed(IDX_BUTTON_L2, true);
        } else {
            state.triggerL = 0.0f; // Simulate release
            state.setPressed(IDX_BUTTON_L2, false);
        }

        if (r > 0.0f) {
            state.triggerR = 1.0f; // Simulate full press
            state.setPressed(IDX_BUTTON_R2, true);
        } else {
            state.triggerR = 0.0f; // Simulate release
            state.setPressed(IDX_BUTTON_R2, false);
        }
    }



//    private void processTriggerButton(MotionEvent event) {
//        // Get the raw analog values of L2 and R2 triggers
//        float l = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) == 0f ? event.getAxisValue(MotionEvent.AXIS_BRAKE) : event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
//        float r = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) == 0f ? event.getAxisValue(MotionEvent.AXIS_GAS) : event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
//
//        // Get the mapped buttons for L2 and R2
//        byte leftTriggerMapped = getMappedButton(IDX_BUTTON_L2);
//        byte rightTriggerMapped = getMappedButton(IDX_BUTTON_R2);
//
//
//
//        // --- Handle button remapping ONLY ---
//        // (Do NOT store original trigger values yet)
//
//        if (leftTriggerMapped == IDX_BUTTON_R2 && rightTriggerMapped == IDX_BUTTON_L2) {
//            // L2 and R2 are swapped
//            state.triggerL = r;
//            state.triggerR = l;
//            state.setPressed(IDX_BUTTON_L2, r == 1.0f);
//            state.setPressed(IDX_BUTTON_R2, l == 1.0f);
////            Log.d("ExternalController", "trigger was swapped");
//        } else {
//        if (leftTriggerMapped != IDX_BUTTON_L2 && leftTriggerMapped != IDX_BUTTON_R2) {
//            // L2 is remapped to a button OTHER than R2
//            state.setPressed(leftTriggerMapped, l > 0.5f);
//            state.triggerL = 0; // Ensure analog value is reset
////            Log.d("ExternalController", "trigger was reset");
//        }
//        if (rightTriggerMapped != IDX_BUTTON_R2 && rightTriggerMapped != IDX_BUTTON_L2) {
//            // R2 is remapped to a button OTHER than L2
//            state.setPressed(rightTriggerMapped, r > 0.5f);
//            state.triggerR = 0; // Ensure analog value is reset
//        }
//
//        // --- Handle trigger cross-mapping ---
//
//        // Reset trigger values to 0 before cross-mapping < Maybe remove this
////        state.triggerL = 0;
////        state.triggerR = 0;
//
//        if (leftTriggerMapped == IDX_BUTTON_R2 && rightTriggerMapped == IDX_BUTTON_L2) {
//            // L2 and R2 are swapped
//            state.triggerL = r;
//            state.triggerR = l;
//        } else if (leftTriggerMapped == IDX_BUTTON_R2 && rightTriggerMapped == IDX_BUTTON_R2) {
//            // BOTH L2 and R2 are mapped to R2
//            state.triggerR = Math.max(l, r);
//        } else if (leftTriggerMapped == IDX_BUTTON_L2 && rightTriggerMapped == IDX_BUTTON_L2) {
//            // BOTH L2 and R2 are mapped to L2
//            state.triggerL = Math.max(l, r);
//        } else {
//            // Not mapping to the same trigger, handle individually
//            if (rightTriggerMapped == IDX_BUTTON_L2) {
//                // R2 is mapped to L2
//                state.triggerL = r;
//            } else if (leftTriggerMapped == IDX_BUTTON_R2) {
//                // L2 is mapped to R2
//                state.triggerR = l;
//            }
//
//            // Set original values if not cross-mapped
//            if (leftTriggerMapped != IDX_BUTTON_R2) {
//                state.triggerL = l;
//            }
//            if (rightTriggerMapped != IDX_BUTTON_L2) {
//                state.triggerR = r;
//            }
//        }
//
//        if (leftTriggerMapped != IDX_BUTTON_L2 && leftTriggerMapped != IDX_BUTTON_R2) {
//            state.triggerL = 0; // Reset L2 analog value if it's mapped to anything else
////            Log.d("ExternalController", "trigger was reset");
//        }
//        if (rightTriggerMapped != IDX_BUTTON_R2 && rightTriggerMapped != IDX_BUTTON_L2) {
//            state.triggerR = 0; // Reset R2 analog value if it's mapped to anything else
//        }
//
//        }
//        // Log for debugging
////        Log.d("ExternalController", "processTriggerButton: L trigger = " + state.triggerL + ", R trigger = " + state.triggerR +
////                ", Mapped L trigger = " + leftTriggerMapped + ", Mapped R trigger = " + rightTriggerMapped);
//    }







//    public boolean updateStateFromMotionEvent(MotionEvent event) {
//        if (isJoystickDevice(event)) {
//            // Check if the event contains trigger axis data
//            boolean hasTriggerData = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) != 0f ||
//                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER) != 0f ||
//                    event.getAxisValue(MotionEvent.AXIS_BRAKE) != 0f ||
//                    event.getAxisValue(MotionEvent.AXIS_GAS) != 0f;
//
//            if (hasTriggerData) {
////                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
////                triggerType = (byte) preferences.getInt("trigger_type", TRIGGER_IS_BUTTON);
//
//                if (triggerType == TRIGGER_IS_AXIS) {
////                    Log.d("ExternalController", "triggerType is " + triggerType);
//                    processTriggerButton(event);
//                }
//            }
//
//            int historySize = event.getHistorySize();
//            for (int i = 0; i < historySize; i++) {
//                processJoystickInput(event, i);
//            }
//            processJoystickInput(event, -1);
//            return true;
//        }
//        return false;
//    }

    public boolean updateStateFromMotionEvent(MotionEvent event) {
        if (isJoystickDevice(event)) {
            processTriggerButton(event);
            int historySize = event.getHistorySize();
            for (int i = 0; i < historySize; i++) processJoystickInput(event, i);
            processJoystickInput(event, -1);
            return true;
        }
        return false;
    }


    public boolean updateStateFromKeyEvent(KeyEvent event) {
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
        int keyCode = event.getKeyCode();
        int buttonIdx = getButtonIdxByKeyCode(keyCode);
        if (buttonIdx != -1) {
            if (buttonIdx == IDX_BUTTON_L2) {
                return true;
            } else if (buttonIdx == IDX_BUTTON_R2) {
                return true;
            } else
                state.setPressed(buttonIdx, pressed);
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                state.dpad[0] = pressed && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                state.dpad[1] = pressed && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                state.dpad[2] = pressed && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                state.dpad[3] = pressed && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
                return true;
        }
        return false;
    }

//    public boolean updateStateFromKeyEvent(KeyEvent event) {
//        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
//        int keyCode = event.getKeyCode();
//        int buttonIdx = getButtonIdxByKeyCode(keyCode);
//
//        if (buttonIdx != -1) {
//            byte mappedButtonIdx = getMappedButton((byte) buttonIdx);
//
//            if (mappedButtonIdx == IDX_BUTTON_L2) {
//                state.triggerL = pressed ? 1.0f : 0f;
//                state.setPressed(mappedButtonIdx, pressed);
//                triggerLPressedViaButton = pressed;
//            } else if (mappedButtonIdx == IDX_BUTTON_R2) {
//                state.triggerR = pressed ? 1.0f : 0f;
//                state.setPressed(mappedButtonIdx, pressed);
//                triggerRPressedViaButton = pressed;
//            } else {
//                state.setPressed(mappedButtonIdx, pressed);
//            }
//            return true;
//        }
//
//        // Handle D-pad directions with mappings
//        switch (keyCode) {
//            case KeyEvent.KEYCODE_DPAD_UP:
//                state.dpad[0] = pressed && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
//                return true;
//            case KeyEvent.KEYCODE_DPAD_RIGHT:
//                state.dpad[1] = pressed && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
//                return true;
//            case KeyEvent.KEYCODE_DPAD_DOWN:
//                state.dpad[2] = pressed && Math.abs(state.thumbLY) < ControlElement.STICK_DEAD_ZONE;
//                return true;
//            case KeyEvent.KEYCODE_DPAD_LEFT:
//                state.dpad[3] = pressed && Math.abs(state.thumbLX) < ControlElement.STICK_DEAD_ZONE;
//                return true;
//        }
//        return false;
//    }




    private static volatile android.content.SharedPreferences blockedPreferences;
    private static volatile java.util.Set<String> blockedDescriptors = java.util.Collections.emptySet();

    public static void initializeBlockedDevices(Context context) {
        blockedPreferences = context.getApplicationContext().getSharedPreferences("blocked_input_devices", Context.MODE_PRIVATE);
        blockedDescriptors = new java.util.HashSet<>(blockedPreferences.getStringSet("descriptors", java.util.Collections.emptySet()));
    }

    public static boolean isBlocked(String descriptor) {
        return blockedDescriptors.contains(descriptor);
    }

    public static synchronized void setBlocked(String descriptor, boolean blocked) {
        java.util.Set<String> next = new java.util.HashSet<>(blockedDescriptors);
        if (blocked) next.add(descriptor); else next.remove(descriptor);
        blockedDescriptors = next;
        if (blockedPreferences != null) blockedPreferences.edit().putStringSet("descriptors", next).apply();
    }

    public static ArrayList<ExternalController> getControllers() {
        return getControllers(false);
    }

    public static ArrayList<ExternalController> getControllers(boolean includeBlocked) {
        int[] deviceIds = InputDevice.getDeviceIds();
        ArrayList<ExternalController> controllers = new ArrayList<>();
        for (int i = deviceIds.length-1; i >= 0; i--) {
            InputDevice device = InputDevice.getDevice(deviceIds[i]);
            if (isControllerCandidate(device) && (includeBlocked || !isBlocked(device.getDescriptor()))) {
                ExternalController controller = new ExternalController();
                controller.setId(device.getDescriptor());
                controller.setName(device.getName());
                controllers.add(controller);
            }
        }
        return controllers;
    }

    public static ExternalController getController(String id) {
        for (ExternalController controller : getControllers()) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public static ExternalController getController(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        if (!isGameController(device)) return null;

        ExternalController controller = new ExternalController();
        controller.setId(device.getDescriptor());
        controller.setName(device.getName());
        controller.deviceId = deviceId;
        return controller;
    }

    public static boolean isGameController(InputDevice device) {
        return isControllerCandidate(device) && !isBlocked(device.getDescriptor());
    }

    private static boolean isControllerCandidate(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        // A mouse can advertise a joystick source without acting as a gamepad.
        boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && (sources & InputDevice.SOURCE_MOUSE) == 0;
        if (device.isVirtual() || (!gamepad && !joystick)) return false;
        if (device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;

        // Some keyboards advertise a gamepad source without any controller controls.
        for (InputDevice.MotionRange range : device.getMotionRanges()) {
            if ((range.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        for (boolean present : device.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_BUTTON_2)) {
            if (present) return true;
        }
        return false;
    }

    public static boolean isControllerKeyEvent(KeyEvent event) {
        InputDevice sourceDevice = event.getDevice();
        if (sourceDevice != null && isBlocked(sourceDevice.getDescriptor())) return false;
        int key = event.getKeyCode();
        if (KeyEvent.isGamepadButton(key)) return true;
        boolean keyboard = event.isFromSource(InputDevice.SOURCE_KEYBOARD);
        if (!keyboard && (event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                || event.isFromSource(InputDevice.SOURCE_JOYSTICK))) return true;

        // Resolve ambiguous navigation keys using the device's capabilities.
        if (key != KeyEvent.KEYCODE_BACK && key != KeyEvent.KEYCODE_HOME
                && (key < KeyEvent.KEYCODE_DPAD_UP || key > KeyEvent.KEYCODE_DPAD_CENTER)) return false;
        InputDevice device = event.getDevice();
        return isGameController(device) && (device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC
                || (!keyboard && event.isFromSource(InputDevice.SOURCE_DPAD)));
    }


    public float getCenteredAxis(MotionEvent event, int axis, int historyPos) {
        if (axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y) {
            float value = event.getAxisValue(axis);
            return Math.abs(value) == 1.0f ? value : 0.0f;
        }

        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range == null) return 0.0f;

        float flat = range.getFlat();
        float value = historyPos < 0 ? event.getAxisValue(axis) : event.getHistoricalAxisValue(axis, historyPos);

        if (Math.abs(value) <= flat) return 0.0f;

        if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y || axis == MotionEvent.AXIS_Z || axis == MotionEvent.AXIS_RZ) {
             return Math.abs(value) >= ControlElement.STICK_DEAD_ZONE ? value : 0.0f;
        }

        return 0.0f;
    }




    public static boolean isJoystickDevice(MotionEvent event) {
        return (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && event.getAction() == MotionEvent.ACTION_MOVE;
    }

    public static int getButtonIdxByKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return IDX_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return IDX_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X:
                return IDX_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return IDX_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return IDX_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return IDX_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return IDX_BUTTON_SELECT;
            case KeyEvent.KEYCODE_BUTTON_START:
                return IDX_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return IDX_BUTTON_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return IDX_BUTTON_R3;
            case KeyEvent.KEYCODE_BUTTON_L2:
                return IDX_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_R2:
                return IDX_BUTTON_R2;
            default:
                return -1;
        }
    }

    public static int getButtonIdxByName(String name) {
        switch (name) {
            case "A":
                return IDX_BUTTON_A;
            case "B":
                return IDX_BUTTON_B;
            case "X":
                return IDX_BUTTON_X;
            case "Y":
                return IDX_BUTTON_Y;
            case "L1":
                return IDX_BUTTON_L1;
            case "R1":
                return IDX_BUTTON_R1;
            case "SELECT":
                return IDX_BUTTON_SELECT;
            case "START":
                return IDX_BUTTON_START;
            case "L3":
                return IDX_BUTTON_L3;
            case "R3":
                return IDX_BUTTON_R3;
            case "L2":
                return IDX_BUTTON_L2;
            case "R2":
                return IDX_BUTTON_R2;
            default:
                return -1;
        }
    }

}
