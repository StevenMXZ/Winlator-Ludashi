package com.winlator.cmod.inputcontrols;

import android.view.KeyEvent;
import androidx.core.provider.FontsContractCompat;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes14.dex */
public class ExternalControllerBinding {
    public static final byte AXIS_RZ_NEGATIVE = -7;
    public static final byte AXIS_RZ_POSITIVE = -8;
    public static final byte AXIS_X_NEGATIVE = -1;
    public static final byte AXIS_X_POSITIVE = -2;
    public static final byte AXIS_Y_NEGATIVE = -3;
    public static final byte AXIS_Y_POSITIVE = -4;
    public static final byte AXIS_Z_NEGATIVE = -5;
    public static final byte AXIS_Z_POSITIVE = -6;
    private Binding binding = Binding.NONE;
    private short keyCode;

    public int getKeyCode() {
        return this.keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = (short) keyCode;
    }

    public Binding getBinding() {
        return this.binding;
    }

    public void setBinding(Binding binding) {
        this.binding = binding;
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject controllerBindingJSONObject = new JSONObject();
            controllerBindingJSONObject.put("keyCode", (int) this.keyCode);
            controllerBindingJSONObject.put("binding", this.binding.name());
            return controllerBindingJSONObject;
        } catch (JSONException e) {
            return null;
        }
    }

    public String toString() {
        switch (this.keyCode) {
            case -8:
                return "AXIS RZ+";
            case -7:
                return "AXIS RZ-";
            case -6:
                return "AXIS Z+";
            case -5:
                return "AXIS Z-";
            case FontsContractCompat.FontRequestCallback.FAIL_REASON_SECURITY_VIOLATION /* -4 */:
                return "AXIS Y+";
            case -3:
                return "AXIS Y-";
            case -2:
                return "AXIS X+";
            case -1:
                return "AXIS X-";
            default:
                return KeyEvent.keyCodeToString(this.keyCode).replace("KEYCODE_", "").replace("_", " ");
        }
    }

    public static int getKeyCodeForAxis(int axis, byte sign) {
        switch (axis) {
            case 0:
                return sign > 0 ? -2 : -1;
            case 1:
                return sign > 0 ? -4 : -3;
            case 11:
                return sign > 0 ? -6 : -5;
            case 14:
                return sign > 0 ? -8 : -7;
            case 15:
                return sign > 0 ? 22 : 21;
            case 16:
                return sign > 0 ? 20 : 19;
            default:
                return 0;
        }
    }
}
