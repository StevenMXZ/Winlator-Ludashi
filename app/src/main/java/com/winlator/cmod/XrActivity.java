package com.winlator.cmod;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.EditText;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.XServer;

/* loaded from: classes8.dex */
public class XrActivity extends XServerDisplayActivity implements TextWatcher {
    private static XrActivity instance;
    private static boolean isDeviceDetectionFinished = false;
    private static boolean isDeviceSupported = false;
    private static boolean isEnabled = false;
    private static boolean isImmersive = false;
    private static boolean isSBS = false;
    private static final KeyCharacterMap chars = KeyCharacterMap.load(-1);
    private static final float[] lastAxes = new float[ControllerAxis.values().length];
    private static final boolean[] lastButtons = new boolean[ControllerButton.values().length];
    private static String lastText = "";
    private static float mouseSpeed = 1.0f;
    private static final float[] smoothedMouse = new float[2];

    public enum ControllerAxis {
        L_PITCH,
        L_YAW,
        L_ROLL,
        L_THUMBSTICK_X,
        L_THUMBSTICK_Y,
        L_X,
        L_Y,
        L_Z,
        R_PITCH,
        R_YAW,
        R_ROLL,
        R_THUMBSTICK_X,
        R_THUMBSTICK_Y,
        R_X,
        R_Y,
        R_Z,
        HMD_PITCH,
        HMD_YAW,
        HMD_ROLL,
        HMD_X,
        HMD_Y,
        HMD_Z,
        HMD_IPD
    }

    public enum ControllerButton {
        L_GRIP,
        L_MENU,
        L_THUMBSTICK_PRESS,
        L_THUMBSTICK_LEFT,
        L_THUMBSTICK_RIGHT,
        L_THUMBSTICK_UP,
        L_THUMBSTICK_DOWN,
        L_TRIGGER,
        L_X,
        L_Y,
        R_A,
        R_B,
        R_GRIP,
        R_THUMBSTICK_PRESS,
        R_THUMBSTICK_LEFT,
        R_THUMBSTICK_RIGHT,
        R_THUMBSTICK_UP,
        R_THUMBSTICK_DOWN,
        R_TRIGGER
    }

    public native boolean beginFrame(boolean z, boolean z2);

    public native void bindFramebuffer();

    public native void endFrame();

    public native float[] getAxes();

    public native boolean[] getButtons();

    public native int getHeight();

    public native int getWidth();

    public native void init();

    @Override // com.winlator.cmod.XServerDisplayActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    public synchronized void onPause() {
        EditText text = (EditText) findViewById(com.ludashi.benchmark.R.id.XRTextInput);
        text.removeTextChangedListener(this);
        super.onPause();
    }

    @Override // com.winlator.cmod.XServerDisplayActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    public synchronized void onResume() {
        super.onResume();
        instance = this;
        mouseSpeed = PreferenceManager.getDefaultSharedPreferences(this).getFloat("cursor_speed", 1.0f);
        EditText text = (EditText) findViewById(com.ludashi.benchmark.R.id.XRTextInput);
        text.setVisibility(0);
        text.getEditableText().clear();
        text.addTextChangedListener(this);
    }

    @Override // com.winlator.cmod.XServerDisplayActivity, androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    public synchronized void onDestroy() {
        super.onDestroy();
        System.exit(0);
    }

    @Override // android.text.TextWatcher
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override // android.text.TextWatcher
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override // android.text.TextWatcher
    public synchronized void afterTextChanged(Editable e) {
        XServer server = instance.getXServer();
        EditText text = (EditText) findViewById(com.ludashi.benchmark.R.id.XRTextInput);
        String s = text.getEditableText().toString();
        if (s.length() > lastText.length()) {
            lastText = s;
            KeyEvent[] events = chars.getEvents(new char[]{s.charAt(s.length() - 1)});
            if (events != null) {
                for (KeyEvent keyEvent : events) {
                    server.keyboard.onKeyEvent(keyEvent);
                    sleep(50);
                }
            }
        } else {
            lastText = s;
            server.keyboard.onKeyEvent(new KeyEvent(0, 67));
            sleep(50);
            server.keyboard.onKeyEvent(new KeyEvent(1, 67));
        }
        if (s.isEmpty()) {
            resetText();
        }
    }

    private synchronized void resetText() {
        EditText text = (EditText) findViewById(com.ludashi.benchmark.R.id.XRTextInput);
        text.removeTextChangedListener(this);
        text.getEditableText().clear();
        text.getEditableText().append((CharSequence) " ");
        text.addTextChangedListener(this);
    }

    public static XrActivity getInstance() {
        return instance;
    }

    public static boolean getImmersive() {
        return isImmersive;
    }

    public static boolean getSBS() {
        return isSBS;
    }

    public static boolean isEnabled(Context context) {
        if (context != null) {
            isEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("use_xr", true);
        }
        return isSupported() && isEnabled;
    }

    public static boolean isSupported() {
        if (!isDeviceDetectionFinished) {
            if (Build.MANUFACTURER.compareToIgnoreCase("META") == 0) {
                isDeviceSupported = true;
            }
            if (Build.MANUFACTURER.compareToIgnoreCase("OCULUS") == 0) {
                isDeviceSupported = true;
            }
            isDeviceDetectionFinished = true;
        }
        return isDeviceSupported;
    }

    public static void openIntent(Activity context, int containerId, String path) {
        Intent intent = new Intent(context, (Class<?>) XrActivity.class);
        intent.putExtra("container_id", containerId);
        if (path != null) {
            intent.putExtra("shortcut_path", path);
        }
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(0);
        intent.setFlags(872448000);
        context.getBaseContext().startActivity(intent, options.toBundle());
        context.finish();
    }

    /* JADX WARN: Code restructure failed: missing block: B:178:0x00f7, code lost:
    
        if (java.lang.Math.abs(r25) > 300.0f) goto L73;
     */
    /* JADX WARN: Removed duplicated region for block: B:73:0x03d0  */
    /* JADX WARN: Removed duplicated region for block: B:76:0x03c2 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static void updateControllers() {
        /*
            Method dump skipped, instructions count: 979
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.XrActivity.updateControllers():void");
    }

    static /* synthetic */ void lambda$updateControllers$0() {
        isSBS = false;
        isImmersive = false;
        instance.resetText();
        AppUtils.showKeyboard(instance);
        instance.findViewById(com.ludashi.benchmark.R.id.XRTextInput).requestFocus();
    }

    private static float getAngleDiff(float oldAngle, float newAngle) {
        float diff = oldAngle - newAngle;
        while (diff > 180.0f) {
            diff -= 360.0f;
        }
        while (diff < -180.0f) {
            diff += 360.0f;
        }
        return diff;
    }

    private static boolean getButtonClicked(boolean[] buttons, ControllerButton button) {
        return buttons[button.ordinal()] && !lastButtons[button.ordinal()];
    }

    private static void mapKey(ControllerButton xrButton, byte xKeycode) {
        Keyboard keyboard = instance.getXServer().keyboard;
        if (lastButtons[xrButton.ordinal()]) {
            keyboard.setKeyPress(xKeycode, 0);
        } else {
            keyboard.setKeyRelease(xKeycode);
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
