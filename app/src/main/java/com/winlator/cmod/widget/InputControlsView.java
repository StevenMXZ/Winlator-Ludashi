package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.widget.FrameLayout;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.ExternalControllerBinding;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

/* loaded from: classes14.dex */
public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private final ColorFilter colorFilter;
    private final Point cursor;
    private boolean editMode;
    private boolean focusOnStick;
    private Runnable hideControlsRunnable;
    private final Bitmap[] icons;
    private final PointF mouseMoveOffset;
    private Timer mouseMoveTimer;
    private boolean moveCursor;
    private float offsetX;
    private float offsetY;
    private float overlayOpacity;
    private final Paint paint;
    private final Path path;
    private SharedPreferences preferences;
    private ControlsProfile profile;
    private boolean readyToDraw;
    private ControlElement selectedElement;
    private boolean showTouchscreenControls;
    private int snappingSize;
    private ControlElement stickElement;
    private Handler timeoutHandler;
    private TouchpadView touchpadView;
    private XServer xServer;

    public boolean isFocusedOnStick() {
        return this.focusOnStick;
    }

    public void setFocusOnStick(boolean focus) {
        this.focusOnStick = focus;
        invalidate();
    }

    public InputControlsView(Context context) {
        super(context);
        this.editMode = false;
        this.paint = new Paint(1);
        this.path = new Path();
        this.colorFilter = new PorterDuffColorFilter(-1, PorterDuff.Mode.SRC_IN);
        this.cursor = new Point();
        this.readyToDraw = false;
        this.moveCursor = false;
        this.overlayOpacity = 0.4f;
        this.icons = new Bitmap[17];
        this.mouseMoveOffset = new PointF();
        this.showTouchscreenControls = true;
        this.focusOnStick = false;
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setBackgroundColor(0);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        this.preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    public InputControlsView(Context context, Handler timeoutHandler, Runnable hideControlsRunnable) {
        super(context);
        this.editMode = false;
        this.paint = new Paint(1);
        this.path = new Path();
        this.colorFilter = new PorterDuffColorFilter(-1, PorterDuff.Mode.SRC_IN);
        this.cursor = new Point();
        this.readyToDraw = false;
        this.moveCursor = false;
        this.overlayOpacity = 0.4f;
        this.icons = new Bitmap[17];
        this.mouseMoveOffset = new PointF();
        this.showTouchscreenControls = true;
        this.focusOnStick = false;
        this.timeoutHandler = timeoutHandler;
        this.hideControlsRunnable = hideControlsRunnable;
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setBackgroundColor(0);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        this.preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    public InputControlsView(Context context, boolean focusOnStick) {
        super(context);
        this.editMode = false;
        this.paint = new Paint(1);
        this.path = new Path();
        this.colorFilter = new PorterDuffColorFilter(-1, PorterDuff.Mode.SRC_IN);
        this.cursor = new Point();
        this.readyToDraw = false;
        this.moveCursor = false;
        this.overlayOpacity = 0.4f;
        this.icons = new Bitmap[17];
        this.mouseMoveOffset = new PointF();
        this.showTouchscreenControls = true;
        this.focusOnStick = false;
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setBackgroundColor(0);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        if (focusOnStick) {
            setLayoutParams(new FrameLayout.LayoutParams(-2, -2));
        } else {
            setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        }
        this.preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public int getSnappingSize() {
        return this.snappingSize;
    }

    @Override // android.view.View
    protected synchronized void onDraw(Canvas canvas) {
        int width;
        int height;
        if (this.stickElement != null && isFocusedOnStick()) {
            Rect boundingBox = this.stickElement.getBoundingBox();
            width = boundingBox.width();
            height = boundingBox.height();
        } else {
            int height2 = getWidth();
            width = height2;
            height = getHeight();
        }
        if (width != 0 && height != 0) {
            this.snappingSize = width / 100;
            this.readyToDraw = true;
            if (this.editMode) {
                drawGrid(canvas);
                drawCursor(canvas);
            }
            if (this.stickElement != null) {
                this.stickElement.draw(canvas);
            }
            if (this.profile != null && this.showTouchscreenControls && !isFocusedOnStick()) {
                if (!this.profile.isElementsLoaded()) {
                    this.profile.loadElements(this);
                }
                for (ControlElement element : this.profile.getElements()) {
                    element.draw(canvas);
                }
            }
            super.onDraw(canvas);
            return;
        }
        this.readyToDraw = false;
    }

    public void resetStickPosition() {
        if (this.stickElement != null) {
            Rect boundingBox = this.stickElement.getBoundingBox();
            float centerX = boundingBox.centerX();
            float centerY = boundingBox.centerY();
            this.stickElement.setCurrentPosition(centerX, centerY);
            invalidate();
        }
    }

    public void initializeStickElement(float x, float y, float scale) {
        this.stickElement = new ControlElement(this);
        this.stickElement.setType(ControlElement.Type.STICK);
        this.stickElement.setX((int) x);
        this.stickElement.setY((int) y);
        this.stickElement.setScale(scale);
        invalidate();
    }

    public void updateStickPosition(float x, float y) {
        if (this.stickElement != null) {
            this.stickElement.getCurrentPosition().x = x;
            this.stickElement.getCurrentPosition().y = y;
            invalidate();
        }
    }

    public ControlElement getStickElement() {
        return this.stickElement;
    }

    private void drawGrid(Canvas canvas) {
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setStrokeWidth(this.snappingSize * 0.0625f);
        this.paint.setColor(ViewCompat.MEASURED_STATE_MASK);
        canvas.drawColor(ViewCompat.MEASURED_STATE_MASK);
        this.paint.setAntiAlias(false);
        this.paint.setColor(-13619152);
        int width = getMaxWidth();
        int height = getMaxHeight();
        int i = 0;
        while (i < width) {
            canvas.drawLine(i, 0.0f, i, height, this.paint);
            canvas.drawLine(0.0f, i, width, i, this.paint);
            i += this.snappingSize;
        }
        float cx = Mathf.roundTo(width * 0.5f, this.snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, this.snappingSize);
        this.paint.setColor(-12434878);
        int i2 = 0;
        while (i2 < width) {
            canvas.drawLine(cx, i2, cx, this.snappingSize + i2, this.paint);
            canvas.drawLine(i2, cy, this.snappingSize + i2, cy, this.paint);
            i2 += this.snappingSize * 2;
        }
        this.paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setStrokeWidth(this.snappingSize * 0.0625f);
        this.paint.setColor(-3790808);
        this.paint.setAntiAlias(false);
        canvas.drawLine(0.0f, this.cursor.y, getMaxWidth(), this.cursor.y, this.paint);
        canvas.drawLine(this.cursor.x, 0.0f, this.cursor.x, getMaxHeight(), this.paint);
        this.paint.setAntiAlias(true);
    }

    public synchronized boolean addElement() {
        if (!this.editMode || this.profile == null) {
            return false;
        }
        ControlElement element = new ControlElement(this);
        element.setX(this.cursor.x);
        element.setY(this.cursor.y);
        this.profile.addElement(element);
        this.profile.save();
        selectElement(element);
        return true;
    }

    public synchronized boolean removeElement() {
        if (!this.editMode || this.selectedElement == null || this.profile == null) {
            return false;
        }
        this.profile.removeElement(this.selectedElement);
        this.selectedElement = null;
        this.profile.save();
        invalidate();
        return true;
    }

    public ControlElement getSelectedElement() {
        return this.selectedElement;
    }

    private synchronized void deselectAllElements() {
        this.selectedElement = null;
        if (this.profile != null) {
            for (ControlElement element : this.profile.getElements()) {
                element.setSelected(false);
            }
        }
    }

    private void selectElement(ControlElement element) {
        deselectAllElements();
        if (element != null) {
            this.selectedElement = element;
            this.selectedElement.setSelected(true);
        }
        invalidate();
    }

    public synchronized ControlsProfile getProfile() {
        return this.profile;
    }

    public synchronized void setProfile(ControlsProfile profile) {
        if (profile != null) {
            this.profile = profile;
            deselectAllElements();
        } else {
            this.profile = null;
        }
    }

    public boolean isShowTouchscreenControls() {
        return this.showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    public int getPrimaryColor() {
        return Color.argb((int) (this.overlayOpacity * 255.0f), 255, 255, 255);
    }

    public int getSecondaryColor() {
        return Color.argb((int) (this.overlayOpacity * 255.0f), 2, 119, 189);
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (this.profile != null) {
            for (ControlElement element : this.profile.getElements()) {
                if (element.containsPoint(x, y)) {
                    return element;
                }
            }
        }
        return null;
    }

    public Paint getPaint() {
        return this.paint;
    }

    public Path getPath() {
        return this.path;
    }

    public ColorFilter getColorFilter() {
        return this.colorFilter;
    }

    public TouchpadView getTouchpadView() {
        return this.touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
    }

    public XServer getXServer() {
        return this.xServer;
    }

    public void setXServer(XServer xServer) {
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() {
        return (int) Mathf.roundTo(getWidth(), this.snappingSize);
    }

    @Override // android.view.View
    protected void onDetachedFromWindow() {
        if (this.mouseMoveTimer != null) {
            this.mouseMoveTimer.cancel();
        }
        super.onDetachedFromWindow();
    }

    public int getMaxHeight() {
        return (int) Mathf.roundTo(getHeight(), this.snappingSize);
    }

    private void createMouseMoveTimer() {
        final WinHandler winHandler = this.xServer.getWinHandler();
        if (this.mouseMoveTimer == null && this.profile != null) {
            final float cursorSpeed = this.profile.getCursorSpeed();
            this.mouseMoveTimer = new Timer();
            this.mouseMoveTimer.schedule(new TimerTask() { // from class: com.winlator.cmod.widget.InputControlsView.1
                @Override // java.util.TimerTask, java.lang.Runnable
                public void run() {
                    if (InputControlsView.this.mouseMoveOffset.x != 0.0f || InputControlsView.this.mouseMoveOffset.y != 0.0f) {
                        if (InputControlsView.this.xServer.isRelativeMouseMovement()) {
                            winHandler.mouseEvent(1, (int) (InputControlsView.this.mouseMoveOffset.x * cursorSpeed * 10.0f), (int) (InputControlsView.this.mouseMoveOffset.y * cursorSpeed * 10.0f), 0);
                        } else {
                            InputControlsView.this.xServer.injectPointerMoveDelta((int) (InputControlsView.this.mouseMoveOffset.x * cursorSpeed * 10.0f), (int) (InputControlsView.this.mouseMoveOffset.y * cursorSpeed * 10.0f));
                        }
                    }
                }
            }, 0L, 16L);
        }
    }

    private void processJoystickInput(ExternalController controller) {
        int[] axes = {0, 1, 11, 14, 15, 16};
        float[] values = {controller.state.thumbLX, controller.state.thumbLY, controller.state.thumbRX, controller.state.thumbRY, controller.state.getDPadX(), controller.state.getDPadY()};
        for (int i = 0; i < axes.length; i++) {
            float value = values[i];
            if (Math.abs(value) > 0.15f) {
                byte sign = Mathf.sign(value);
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
                if (controllerBinding != null) {
                    handleInputEvent(controller, controllerBinding.getBinding(), true, value, false);
                }
            } else {
                for (byte sign2 = -1; sign2 <= 1; sign2 = (byte) (sign2 + 2)) {
                    int keyCode2 = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign2);
                    ExternalControllerBinding controllerBinding2 = controller.getControllerBinding(keyCode2);
                    if (controllerBinding2 != null) {
                        handleInputEvent(controller, controllerBinding2.getBinding(), false, value, false);
                    }
                }
            }
        }
        processTriggerInput(controller, controller.state.triggerL, 104, false);
        processTriggerInput(controller, controller.state.triggerR, 105, false);
        WinHandler winHandler = this.xServer != null ? this.xServer.getWinHandler() : null;
        if (winHandler != null) {
            winHandler.sendGamepadState(controller);
        }
    }

    private void processTriggerInput(ExternalController controller, float value, int keyCode, boolean sendUpdate) {
        ExternalControllerBinding binding = controller.getControllerBinding(keyCode);
        if (binding != null) {
            boolean isPressed = value > 0.15f;
            if (isPressed) {
                handleInputEvent(controller, binding.getBinding(), true, value, sendUpdate);
            } else {
                handleInputEvent(controller, binding.getBinding(), false, 0.0f, sendUpdate);
            }
        }
    }

    @Override // android.view.View
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        Log.d("InputControlsView", "dispatchGenericMotionEvent called. Source: " + event.getSource());
        return super.dispatchGenericMotionEvent(event);
    }

    @Override // android.view.View
    public boolean onGenericMotionEvent(MotionEvent event) {
        ExternalController controller;
        Log.d("InputControlsView", "Motion event received. Source: " + event.getSource());
        Log.d("InputControlsView", "Device ID: " + event.getDeviceId());
        Log.d("InputControlsView", "Profile is " + (this.profile != null ? "set" : "null"));
        if (!this.editMode && this.profile != null && (controller = this.profile.getController(event.getDeviceId())) != null && controller.updateStateFromMotionEvent(event)) {
            ExternalControllerBinding controllerBinding = controller.getControllerBinding(104);
            if (controllerBinding != null) {
                handleInputEvent(controller, controllerBinding.getBinding(), controller.state.isPressed(10));
            }
            ExternalControllerBinding controllerBinding2 = controller.getControllerBinding(105);
            if (controllerBinding2 != null) {
                handleInputEvent(controller, controllerBinding2.getBinding(), controller.state.isPressed(11));
            }
            Log.d("InputEvent", "Event source: " + event.getSource());
            Log.d("InputEvent", "Device ID: " + event.getDeviceId());
            Log.d("InputEvent", "Action: " + event.getAction());
            processJoystickInput(controller);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override // android.view.View
    public boolean onTouchEvent(MotionEvent event) {
        Vibrator vibrator;
        boolean hapticsEnabled = this.preferences.getBoolean("touchscreen_haptics_enabled", true);
        resetTouchscreenTimeout();
        if (this.editMode && this.readyToDraw) {
            switch (event.getAction()) {
                case 0:
                    float x = event.getX();
                    float y = event.getY();
                    ControlElement element = intersectElement(x, y);
                    this.moveCursor = true;
                    if (element != null) {
                        this.offsetX = x - element.getX();
                        this.offsetY = y - element.getY();
                        this.moveCursor = false;
                    }
                    selectElement(element);
                    break;
                case 1:
                    if (this.selectedElement != null && this.profile != null) {
                        this.profile.save();
                    }
                    if (this.moveCursor) {
                        this.cursor.set((int) Mathf.roundTo(event.getX(), this.snappingSize), (int) Mathf.roundTo(event.getY(), this.snappingSize));
                    }
                    invalidate();
                    break;
                case 2:
                    if (this.selectedElement != null) {
                        this.selectedElement.setX((int) Mathf.roundTo(event.getX() - this.offsetX, this.snappingSize));
                        this.selectedElement.setY((int) Mathf.roundTo(event.getY() - this.offsetY, this.snappingSize));
                        invalidate();
                        break;
                    }
                    break;
            }
        }
        if (!this.editMode && this.profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;
            switch (actionMasked) {
                case 0:
                case 5:
                    float x2 = event.getX(actionIndex);
                    float y2 = event.getY(actionIndex);
                    this.touchpadView.setPointerButtonLeftEnabled(true);
                    for (ControlElement element2 : this.profile.getElements()) {
                        if (element2.handleTouchDown(pointerId, x2, y2)) {
                            handled = true;
                            if (hapticsEnabled && (vibrator = (Vibrator) getContext().getSystemService("vibrator")) != null && vibrator.hasVibrator()) {
                                vibrator.vibrate(VibrationEffect.createOneShot(50L, -1));
                            }
                        }
                        if (element2.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            this.touchpadView.setPointerButtonLeftEnabled(false);
                        }
                    }
                    if (!handled) {
                        this.touchpadView.onTouchEvent(event);
                        break;
                    }
                    break;
                case 1:
                case 3:
                case 6:
                    Iterator<ControlElement> it = this.profile.getElements().iterator();
                    while (it.hasNext()) {
                        if (it.next().handleTouchUp(pointerId)) {
                            handled = true;
                        }
                    }
                    if (!handled) {
                        this.touchpadView.onTouchEvent(event);
                        break;
                    }
                    break;
                case 2:
                    byte count = (byte) event.getPointerCount();
                    for (byte i = 0; i < count; i = (byte) (i + 1)) {
                        float x3 = event.getX(i);
                        float y3 = event.getY(i);
                        boolean handled2 = false;
                        Iterator<ControlElement> it2 = this.profile.getElements().iterator();
                        while (it2.hasNext()) {
                            if (it2.next().handleTouchMove(i, x3, y3)) {
                                handled2 = true;
                            }
                        }
                        if (!handled2) {
                            this.touchpadView.onTouchEvent(event);
                        }
                    }
                    break;
            }
            return true;
        }
        return true;
    }

    private void resetTouchscreenTimeout() {
        Log.d("InputControlsView", "Touch detected, resetting timeout.");
        if (this.timeoutHandler != null && this.hideControlsRunnable != null) {
            this.timeoutHandler.removeCallbacks(this.hideControlsRunnable);
            this.timeoutHandler.postDelayed(this.hideControlsRunnable, 5000L);
        }
    }

    public boolean onKeyEvent(KeyEvent event) {
        ExternalController controller;
        ExternalControllerBinding controllerBinding;
        if (this.profile == null || event.getRepeatCount() != 0 || (controller = this.profile.getController(event.getDeviceId())) == null || (controllerBinding = controller.getControllerBinding(event.getKeyCode())) == null) {
            return false;
        }
        int action = event.getAction();
        if (action == 0) {
            handleInputEvent(controller, controllerBinding.getBinding(), true);
        } else if (action == 1) {
            handleInputEvent(controller, controllerBinding.getBinding(), false);
        }
        return true;
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(null, binding, isActionDown, 0.0f);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown) {
        handleInputEvent(controller, binding, isActionDown, 0.0f);
    }

    public void handleStickInput(Binding firstBinding, float deltaX, float deltaY) {
        if (firstBinding.isGamepad()) {
            GamepadState state = this.profile.getGamepadState();
            WinHandler winHandler = this.xServer != null ? this.xServer.getWinHandler() : null;
            boolean isLeftStick = firstBinding == Binding.GAMEPAD_LEFT_THUMB_UP || firstBinding == Binding.GAMEPAD_LEFT_THUMB_DOWN || firstBinding == Binding.GAMEPAD_LEFT_THUMB_LEFT || firstBinding == Binding.GAMEPAD_LEFT_THUMB_RIGHT;
            if (isLeftStick) {
                state.thumbLX = deltaX;
                state.thumbLY = deltaY;
            } else {
                state.thumbRX = deltaX;
                state.thumbRY = deltaY;
            }
            if (winHandler != null) {
                winHandler.sendGamepadState();
            }
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(null, binding, isActionDown, offset);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(controller, binding, isActionDown, offset, true);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset, boolean sendUpdate) {
        int wheelDelta;
        WinHandler winHandler = this.xServer != null ? this.xServer.getWinHandler() : null;
        if (binding.isGamepad()) {
            GamepadState state = controller != null ? controller.remappedState : this.profile.getGamepadState();
            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= 11) {
                if (buttonIdx == 10) {
                    state.triggerL = isActionDown ? offset != 0.0f ? offset : 1.0f : 0.0f;
                } else if (buttonIdx == 11) {
                    state.triggerR = isActionDown ? offset != 0.0f ? offset : 1.0f : 0.0f;
                } else {
                    state.setPressed(buttonIdx, isActionDown);
                }
            } else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                float val = (isActionDown && offset == 0.0f) ? 1.0f : Math.abs(offset);
                state.thumbLY = isActionDown ? binding == Binding.GAMEPAD_LEFT_THUMB_UP ? -val : val : 0.0f;
            } else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                float val2 = (isActionDown && offset == 0.0f) ? 1.0f : Math.abs(offset);
                state.thumbLX = isActionDown ? binding == Binding.GAMEPAD_LEFT_THUMB_LEFT ? -val2 : val2 : 0.0f;
            } else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                float val3 = (isActionDown && offset == 0.0f) ? 1.0f : Math.abs(offset);
                state.thumbRY = isActionDown ? binding == Binding.GAMEPAD_RIGHT_THUMB_UP ? -val3 : val3 : 0.0f;
            } else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                float val4 = (isActionDown && offset == 0.0f) ? 1.0f : Math.abs(offset);
                state.thumbRX = isActionDown ? binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT ? -val4 : val4 : 0.0f;
            } else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT || binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = isActionDown;
            }
            if (winHandler != null && sendUpdate) {
                if (controller != null) {
                    winHandler.sendGamepadState(controller);
                    return;
                } else {
                    winHandler.sendGamepadState();
                    return;
                }
            }
            return;
        }
        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
            PointF pointF = this.mouseMoveOffset;
            if (isActionDown) {
                if (offset != 0.0f) {
                    r2 = offset;
                } else {
                    r2 = binding != Binding.MOUSE_MOVE_LEFT ? 1 : -1;
                }
            }
            pointF.x = r2;
            if (isActionDown) {
                createMouseMoveTimer();
                return;
            }
            return;
        }
        if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
            PointF pointF2 = this.mouseMoveOffset;
            if (isActionDown) {
                if (offset != 0.0f) {
                    r2 = offset;
                } else {
                    r2 = binding != Binding.MOUSE_MOVE_UP ? 1 : -1;
                }
            }
            pointF2.y = r2;
            if (isActionDown) {
                createMouseMoveTimer();
                return;
            }
            return;
        }
        Pointer.Button pointerButton = binding.getPointerButton();
        if (isActionDown) {
            if (pointerButton != null) {
                if (this.xServer.isRelativeMouseMovement()) {
                    if (pointerButton == Pointer.Button.BUTTON_SCROLL_UP) {
                        wheelDelta = 120;
                    } else {
                        wheelDelta = pointerButton == Pointer.Button.BUTTON_SCROLL_DOWN ? -120 : 0;
                    }
                    winHandler.mouseEvent(MouseEventFlags.getFlagFor(pointerButton, true), 0, 0, wheelDelta);
                    return;
                }
                this.xServer.injectPointerButtonPress(pointerButton);
                return;
            }
            this.xServer.injectKeyPress(binding.keycode);
            return;
        }
        if (pointerButton != null) {
            if (this.xServer.isRelativeMouseMovement()) {
                winHandler.mouseEvent(MouseEventFlags.getFlagFor(pointerButton, false), 0, 0, 0);
                return;
            } else {
                this.xServer.injectPointerButtonRelease(pointerButton);
                return;
            }
        }
        this.xServer.injectKeyRelease(binding.keycode);
    }

    public Bitmap getIcon(byte id) {
        if (this.icons[id] == null) {
            Context context = getContext();
            try {
                InputStream is = context.getAssets().open("inputcontrols/icons/" + ((int) id) + ".png");
                try {
                    this.icons[id] = BitmapFactory.decodeStream(is);
                    if (is != null) {
                        is.close();
                    }
                } finally {
                }
            } catch (IOException e) {
            }
        }
        return this.icons[id];
    }
}
