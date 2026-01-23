package com.winlator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.winlator.input.InputStub;
import com.winlator.input.TouchInputHandler;

import java.nio.charset.StandardCharsets;
import java.util.regex.PatternSyntaxException;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

@Keep @SuppressLint("WrongConstant")
@SuppressWarnings("deprecation")
public class LorieView extends SurfaceView implements InputStub {
    public interface Callback {
        void changed(Surface sfc, int surfaceWidth, int surfaceHeight, int screenWidth, int screenHeight);
    }

    interface PixelFormat {
        int BGRA_8888 = 5; // Stands for HAL_PIXEL_FORMAT_BGRA_8888
    }

    private ClipboardManager clipboard;
    private long lastClipboardTimestamp = System.currentTimeMillis();
    private static boolean clipboardSyncEnabled = false;
    private static boolean hardwareKbdScancodesWorkaround = false;
    private final InputMethodManager mIMM = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    private String mImeLang;
    private boolean mImeCJK;
    public boolean enableGboardCJK;
    private Callback mCallback;
    private final Point p = new Point();
    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
            holder.setFormat(PixelFormat.BGRA_8888);
        }

        @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int f, int width, int height) {
            width = getMeasuredWidth();
            height = getMeasuredHeight();

            Log.d("SurfaceChangedListener", "Surface was changed: " + width + "x" + height);
            if (mCallback == null)
                return;

            getDimensionsFromSettings();
            if (mCallback != null)
                mCallback.changed(holder.getSurface(), width, height, p.x, p.y);
            LorieView.this.surfaceChanged(holder.getSurface());
        }

        @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            if (mCallback != null)
                mCallback.changed(holder.getSurface(), 0, 0, 0, 0);
            LorieView.this.surfaceChanged(holder.getSurface());
        }
    };

    public LorieView(Context context) { super(context); init(); }
    public LorieView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public LorieView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }
    @SuppressWarnings("unused")
    public LorieView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); init(); }

    private void init() {
        getHolder().addCallback(mSurfaceCallback);
        clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        nativeInit();
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
        triggerCallback();
    }

    public void regenerate() {
        Callback callback = mCallback;
        mCallback = null;
        getHolder().setFormat(android.graphics.PixelFormat.RGBA_8888);
        mCallback = callback;

        triggerCallback();
    }

    public void triggerCallback() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();

        setBackground(new ColorDrawable(Color.TRANSPARENT) {
            public boolean isStateful() {
                return true;
            }
            public boolean hasFocusStateSpecified() {
                return true;
            }
        });

        Rect r = getHolder().getSurfaceFrame();
        getActivity().runOnUiThread(() -> mSurfaceCallback.surfaceChanged(getHolder(), PixelFormat.BGRA_8888, r.width(), r.height()));
    }

    private Activity getActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }

        throw new NullPointerException();
    }

    void getDimensionsFromSettings() {
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        int w = width;
        int h = height;
        switch(preferences.getString("displayResolutionMode", "native")) {
            case "scaled": {
                int scale = preferences.getInt("displayScale", 100);
                w = width / scale * 100;
                h = height / scale * 100;
                break;
            }
            case "exact": {
                String[] resolution = preferences.getString("displayResolutionExact", "1280x1024").split("x");
                w = Integer.parseInt(resolution[0]);
                h = Integer.parseInt(resolution[1]);
                Log.d("LorieView", w + " x " + h);
                break;
            }
            case "custom": {
                try {
                    String[] resolution = preferences.getString("displayResolutionCustom", "1280x1024").split("x");
                    w = Integer.parseInt(resolution[0]);
                    h = Integer.parseInt(resolution[1]);
                } catch (NumberFormatException | PatternSyntaxException ignored) {
                    w = 1280;
                    h = 1024;
                }
                break;
            }
        }

        /*String[] resolution = (selectedResolution != null ? selectedResolution : "1280x720").split("x");

        int w = Integer.parseInt(resolution[0]);
        int h = Integer.parseInt(resolution[1]);*/

        if ((width < height && w > h) || (width > height && w < h)) {
            p.set(h, w);
        } else {
            p.set(w, h);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        /*
        if (preferences?.getBoolean("displayStretch", false) == true) {
            holder.setSizeFromLayout()
            return
        }
         */

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());

        if (preferences != null) {
            if (preferences.getBoolean("displayStretch", false)) {
                getHolder().setSizeFromLayout();
                return;
            }
        }

        getDimensionsFromSettings();

        if (p.x <= 0 || p.y <= 0)
            return;

        int width = getMeasuredWidth();
        int height = getMeasuredHeight();

        if ((width < height && p.x > p.y) || (width > height && p.x < p.y))
            //noinspection SuspiciousNameCombination
            p.set(p.y, p.x);

        if (width > height * p.x / p.y) {
            width = height * p.x / p.y;
        } else {
            height = width * p.y / p.x;
        }

        getHolder().setFixedSize(p.x, p.y);
        setMeasuredDimension(width, height);

        // In the case if old fixed surface size equals new fixed surface size windowChanged will not be called.
        // We should force it.
        regenerate();
    }

    @Override
    public void sendMouseWheelEvent(float deltaX, float deltaY) {
        sendMouseEvent(deltaX, deltaY, BUTTON_SCROLL, false, true);
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if (hardwareKbdScancodesWorkaround) return false;
        Activity a = getActivity();
        return (a instanceof X11Activity) && ((X11Activity) a).handleKey(event);
    }

    ClipboardManager.OnPrimaryClipChangedListener clipboardListener = this::handleClipboardChange;

    public void reloadPreferences() {
        hardwareKbdScancodesWorkaround = false;
        clipboardSyncEnabled = true;
        setClipboardSyncEnabled(true, true);
        TouchInputHandler.refreshInputDevices();
    }

    // It is used in native code
    void setClipboardText(String text) {
        clipboard.setPrimaryClip(ClipData.newPlainText("X11 clipboard", text));

        // Android does not send PrimaryClipChanged event to the window which posted event
        // But in the case we are owning focus and clipboard is unchanged it will be replaced by the same value on X server side.
        // Not cool in the case if user installed some clipboard manager, clipboard content will be doubled.
        lastClipboardTimestamp = System.currentTimeMillis() + 150;
    }

    /** @noinspection unused*/ // It is used in native code
    void requestClipboard() {
        if (!clipboardSyncEnabled) {
            sendClipboardEvent("".getBytes(StandardCharsets.UTF_8));
            return;
        }

        CharSequence clip = clipboard.getText();
        if (clip != null) {
            String text = String.valueOf(clipboard.getText());
            sendClipboardEvent(text.getBytes(StandardCharsets.UTF_8));
            Log.d("CLIP", "sending clipboard contents: " + text);
        }
    }

    public void handleClipboardChange() {
        checkForClipboardChange();
    }

    public void checkForClipboardChange() {
        ClipDescription desc = clipboard.getPrimaryClipDescription();
        if (clipboardSyncEnabled && desc != null &&
                lastClipboardTimestamp < desc.getTimestamp() &&
                desc.getMimeTypeCount() == 1 &&
                (desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                        desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) {
            lastClipboardTimestamp = desc.getTimestamp();
            sendClipboardAnnounce();
            Log.d("CLIP", "sending clipboard announce");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            regenerate();

        requestFocus();

        if (clipboardSyncEnabled && hasFocus) {
            clipboard.addPrimaryClipChangedListener(clipboardListener);
            checkForClipboardChange();
        } else
            clipboard.removePrimaryClipChangedListener(clipboardListener);

        TouchInputHandler.refreshInputDevices();
    }

    public void checkRestartInput(boolean recheck) {
        if (!enableGboardCJK)
            return;

        InputMethodSubtype methodSubtype = mIMM.getCurrentInputMethodSubtype();
        String languageTag = methodSubtype == null ? null : methodSubtype.getLanguageTag();
        if (languageTag != null && languageTag.length() >= 2 && !languageTag.substring(0, 2).equals(mImeLang))
            mIMM.restartInput(this);
        else if (recheck) { // recheck needed because sometimes requestCursorUpdates() is called too fast, before InputMethodManager detect change in IM subtype
            X11Activity.handler.postDelayed(() -> checkRestartInput(false), 40);
        }
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV (see https://github.com/termux/termux-app/issues/221).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;

        if (enableGboardCJK) {
            InputMethodSubtype methodSubtype = mIMM.getCurrentInputMethodSubtype();
            mImeLang = methodSubtype == null ? null : methodSubtype.getLanguageTag();
            if (mImeLang != null && mImeLang.length() > 2)
                mImeLang = mImeLang.substring(0, 2);
            mImeCJK = mImeLang != null && (mImeLang.equals("zh") || mImeLang.equals("ko") || mImeLang.equals("ja"));
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                    (mImeCJK ? InputType.TYPE_TEXT_VARIATION_NORMAL : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            return new BaseInputConnection(this, false) {
                // workaround for Gboard
                // Gboard calls requestCursorUpdates() whenever switching language
                // check and then restart keyboard in different inputType when needed
                @Override
                public Editable getEditable() {
                    checkRestartInput(true);
                    return super.getEditable();
                }
                @Override
                public boolean requestCursorUpdates(int cursorUpdateMode) {
                    checkRestartInput(true);
                    return super.requestCursorUpdates(cursorUpdateMode);
                }

                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    boolean result = super.commitText(text, newCursorPosition);
                    if (mImeCJK)
                        // suppress Gboard CJK keyboard suggestion
                        // this workaround does not work well for non-CJK keyboards
                        // , when typing fast and two keypresses (commitText) are close in time
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            mIMM.invalidateInput(LorieView.this);
                        else
                            mIMM.restartInput(LorieView.this);
                    return result;
                }
            };
        } else {
            return super.onCreateInputConnection(outAttrs);
        }
    }

    public static native boolean renderingInActivity();
    @FastNative private native void nativeInit();
    @FastNative private native void surfaceChanged(Surface surface);
    @FastNative public static native void connect(int fd);
    @CriticalNative public static native boolean connected();
    @FastNative public static native void startLogcat(int fd);
    @FastNative public static native void setClipboardSyncEnabled(boolean enabled, boolean ignored);
    @FastNative public native void sendClipboardAnnounce();
    @FastNative public native void sendClipboardEvent(byte[] text);
    @FastNative public static native void sendWindowChange(int width, int height, int framerate, String name);
    @FastNative public native void sendMouseEvent(float x, float y, int whichButton, boolean buttonDown, boolean relative);
    @FastNative public native void sendTouchEvent(int action, int id, int x, int y);
    @FastNative public native void sendStylusEvent(float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouseMode);
    @FastNative static public native void requestStylusEnabled(boolean enabled);
    @FastNative public native boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown);
    @FastNative public native void sendTextEvent(byte[] text);
    @CriticalNative public static native void requestConnection();

    static {
        System.loadLibrary("Xlorie");
    }
}
