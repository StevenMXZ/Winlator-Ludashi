package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.widget.FrameLayout;
import androidx.preference.PreferenceManager;
import com.ludashi.benchmark.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.ClientOpcodes;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XServer;
import org.apache.commons.compress.archivers.tar.TarConstants;

/* loaded from: classes14.dex */
public class TouchpadView extends View {
    public static final float CURSOR_ACCELERATION = 1.25f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private static final byte MAX_FINGERS = 4;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    private static final short MAX_TWO_FINGERS_SCROLL_DISTANCE = 350;
    private static final int UPDATE_FORM_DELAYED_TIME = 50;
    private boolean continueClick;
    private Finger fingerPointerButtonLeft;
    private Finger fingerPointerButtonRight;
    private final Finger[] fingers;
    private Runnable fourFingersTapCallback;
    private Runnable hideControlsRunnable;
    private int lastTouchedPosX;
    private int lastTouchedPosY;
    private boolean mouseEnabled;
    private byte numFingers;
    private boolean pointerButtonLeftEnabled;
    private boolean pointerButtonRightEnabled;
    private SharedPreferences preferences;
    private float resolutionScale;
    private float scrollAccumY;
    private boolean scrolling;
    private float sensitivity;
    private boolean simTouchScreen;
    private Handler timeoutHandler;
    private final XServer xServer;
    private final float[] xform;
    private static final Byte CLICK_DELAYED_TIME = Byte.valueOf(TarConstants.LF_SYMLINK);
    private static final Byte EFFECTIVE_TOUCH_DISTANCE = Byte.valueOf(ClientOpcodes.GET_PROPERTY);

    public TouchpadView(Context context, XServer xServer, Handler timeoutHandler, Runnable hideControlsRunnable) {
        super(context);
        this.fingers = new Finger[4];
        this.numFingers = (byte) 0;
        this.sensitivity = 1.0f;
        this.pointerButtonLeftEnabled = true;
        this.pointerButtonRightEnabled = true;
        this.scrollAccumY = 0.0f;
        this.scrolling = false;
        this.xform = XForm.getInstance();
        this.simTouchScreen = false;
        this.continueClick = true;
        this.mouseEnabled = true;
        this.xServer = xServer;
        this.timeoutHandler = timeoutHandler;
        this.hideControlsRunnable = hideControlsRunnable;
        setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        setBackground(createTransparentBg());
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo.width, xServer.screenInfo.height);
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.timeoutHandler = timeoutHandler;
        this.hideControlsRunnable = hideControlsRunnable;
        setOnGenericMotionListener(new View.OnGenericMotionListener() { // from class: com.winlator.cmod.widget.TouchpadView.1
            @Override // android.view.View.OnGenericMotionListener
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (event.getToolType(0) != 2) {
                    return false;
                }
                return TouchpadView.this.handleStylusHoverEvent(event);
            }
        });
    }

    @Override // android.view.View
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateXform(w, h, this.xServer.screenInfo.width, this.xServer.screenInfo.height);
        this.resolutionScale = 1000.0f / Math.min((int) this.xServer.screenInfo.width, (int) this.xServer.screenInfo.height);
    }

    private void updateXform(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        ViewTransformation viewTransformation = new ViewTransformation();
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight);
        float invAspect = 1.0f / viewTransformation.aspect;
        if (!this.xServer.getRenderer().isFullscreen()) {
            XForm.makeTranslation(this.xform, -viewTransformation.viewOffsetX, -viewTransformation.viewOffsetY);
            XForm.scale(this.xform, invAspect, invAspect);
        } else {
            XForm.makeScale(this.xform, innerWidth / outerWidth, innerHeight / outerHeight);
        }
    }

    private class Finger {
        private int lastX;
        private int lastY;
        private final int startX;
        private final int startY;
        private final long touchTime;
        private int x;
        private int y;

        public Finger(float x, float y) {
            float[] transformedPoint = XForm.transformPoint(TouchpadView.this.xform, x, y);
            int i = (int) transformedPoint[0];
            this.lastX = i;
            this.startX = i;
            this.x = i;
            int i2 = (int) transformedPoint[1];
            this.lastY = i2;
            this.startY = i2;
            this.y = i2;
            this.touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            this.lastX = this.x;
            this.lastY = this.y;
            float[] transformedPoint = XForm.transformPoint(TouchpadView.this.xform, x, y);
            this.x = (int) transformedPoint[0];
            this.y = (int) transformedPoint[1];
        }

        /* JADX INFO: Access modifiers changed from: private */
        public int deltaX() {
            float dx = (this.x - this.lastX) * TouchpadView.this.sensitivity;
            if (Math.abs(dx) > 6.0f) {
                dx *= 1.25f;
            }
            return Mathf.roundPoint(dx);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public int deltaY() {
            float dy = (this.y - this.lastY) * TouchpadView.this.sensitivity;
            if (Math.abs(dy) > 6.0f) {
                dy *= 1.25f;
            }
            return Mathf.roundPoint(dy);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isTap() {
            return System.currentTimeMillis() - this.touchTime < 200 && travelDistance() < 10.0f;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public float travelDistance() {
            return (float) Math.hypot(this.x - this.startX, this.y - this.startY);
        }
    }

    @Override // android.view.View
    public boolean onTouchEvent(MotionEvent event) {
        if (!this.mouseEnabled) {
            return true;
        }
        boolean isTouchscreenMode = this.preferences.getBoolean("touchscreen_toggle", false);
        resetTouchscreenTimeout();
        int toolType = event.getToolType(0);
        if (toolType == 2) {
            return handleStylusEvent(event);
        }
        if (isTouchscreenMode) {
            return handleTouchscreenEvent(event);
        }
        return handleTouchpadEvent(event);
    }

    private void resetTouchscreenTimeout() {
        if (this.timeoutHandler != null && this.hideControlsRunnable != null) {
            this.timeoutHandler.removeCallbacks(this.hideControlsRunnable);
            this.timeoutHandler.postDelayed(this.hideControlsRunnable, 5000L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean handleStylusHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case 7:
                Log.d("StylusEvent", "Hover Move: (" + event.getX() + ", " + event.getY() + ")");
                float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
                this.xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
                return true;
            case 8:
            default:
                return false;
            case 9:
                Log.d("StylusEvent", "Hover Enter");
                return true;
            case 10:
                Log.d("StylusEvent", "Hover Exit");
                return true;
        }
    }

    private boolean handleStylusEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int buttonState = event.getButtonState();
        switch (action) {
            case 0:
                if ((buttonState & 2) != 0) {
                    handleStylusRightClick(event);
                    break;
                } else {
                    handleStylusLeftClick(event);
                    break;
                }
            case 1:
                handleStylusUp(event);
                break;
            case 2:
                handleStylusMove(event);
                break;
        }
        return true;
    }

    private void handleStylusLeftClick(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
        this.xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
        this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
    }

    private void handleStylusRightClick(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
        this.xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
        this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
    }

    private void handleStylusMove(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
        this.xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
    }

    private void handleStylusUp(MotionEvent event) {
        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private boolean handleTouchpadEvent(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();
        if (pointerId >= 4) {
            return true;
        }
        switch (actionMasked) {
            case 0:
            case 5:
                if (event.isFromSource(8194)) {
                    return true;
                }
                this.scrollAccumY = 0.0f;
                this.scrolling = false;
                this.fingers[pointerId] = new Finger(event.getX(actionIndex), event.getY(actionIndex));
                this.numFingers = (byte) (this.numFingers + 1);
                if (this.simTouchScreen) {
                    Runnable clickDelay = new Runnable() { // from class: com.winlator.cmod.widget.TouchpadView$$ExternalSyntheticLambda4
                        @Override // java.lang.Runnable
                        public final void run() {
                            TouchpadView.this.lambda$handleTouchpadEvent$0();
                        }
                    };
                    if (pointerId == 0) {
                        this.continueClick = true;
                        if (Math.hypot(this.fingers[0].x - this.lastTouchedPosX, this.fingers[0].y - this.lastTouchedPosY) * this.resolutionScale > EFFECTIVE_TOUCH_DISTANCE.byteValue()) {
                            this.lastTouchedPosX = this.fingers[0].x;
                            this.lastTouchedPosY = this.fingers[0].y;
                        }
                        postDelayed(clickDelay, CLICK_DELAYED_TIME.byteValue());
                    } else if (pointerId == 1) {
                        if (this.numFingers < 2) {
                            this.continueClick = true;
                            if (Math.hypot(this.fingers[1].x - this.lastTouchedPosX, this.fingers[1].y - this.lastTouchedPosY) * this.resolutionScale > EFFECTIVE_TOUCH_DISTANCE.byteValue()) {
                                this.lastTouchedPosX = this.fingers[1].x;
                                this.lastTouchedPosY = this.fingers[1].y;
                            }
                            postDelayed(clickDelay, CLICK_DELAYED_TIME.byteValue());
                        } else {
                            this.continueClick = System.currentTimeMillis() - this.fingers[0].touchTime > ((long) CLICK_DELAYED_TIME.byteValue());
                        }
                    }
                }
                return true;
            case 1:
            case 6:
                if (this.fingers[pointerId] != null) {
                    this.fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    handleFingerUp(this.fingers[pointerId]);
                    this.fingers[pointerId] = null;
                    this.numFingers = (byte) (this.numFingers - 1);
                }
                return true;
            case 2:
                if (event.isFromSource(8194)) {
                    float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
                    if (this.xServer.isRelativeMouseMovement()) {
                        this.xServer.getWinHandler().mouseEvent(1, (int) transformedPoint[0], (int) transformedPoint[1], 0);
                    } else {
                        this.xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
                    }
                } else {
                    for (byte i = 0; i < 4; i = (byte) (i + 1)) {
                        if (this.fingers[i] != null) {
                            int pointerIndex = event.findPointerIndex(i);
                            if (pointerIndex >= 0) {
                                this.fingers[i].update(event.getX(pointerIndex), event.getY(pointerIndex));
                                handleFingerMove(this.fingers[i]);
                            } else {
                                handleFingerUp(this.fingers[i]);
                                this.fingers[i] = null;
                                this.numFingers = (byte) (this.numFingers - 1);
                            }
                        }
                    }
                }
                return true;
            case 3:
                for (byte i2 = 0; i2 < 4; i2 = (byte) (i2 + 1)) {
                    this.fingers[i2] = null;
                }
                this.numFingers = (byte) 0;
                return true;
            case 4:
            default:
                return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$handleTouchpadEvent$0() {
        if (this.continueClick) {
            this.xServer.injectPointerMove(this.lastTouchedPosX, this.lastTouchedPosY);
            this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        }
    }

    private boolean handleTouchscreenEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case 0:
            case 5:
                handleTouchDown(event);
                break;
            case 1:
            case 6:
                if (event.getPointerCount() == 2) {
                    handleTwoFingerTap(event);
                    break;
                } else {
                    handleTouchUp(event);
                    break;
                }
            case 2:
                if (event.getPointerCount() == 2) {
                    handleTwoFingerScroll(event);
                    break;
                } else {
                    handleTouchMove(event);
                    break;
                }
            case 3:
                if (this.xServer.isRelativeMouseMovement()) {
                    this.xServer.getWinHandler().mouseEvent(4, 0, 0, 0);
                    this.xServer.getWinHandler().mouseEvent(16, 0, 0, 0);
                    break;
                } else {
                    this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                    break;
                }
        }
        return true;
    }

    private void handleTouchDown(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
        if (this.xServer.isRelativeMouseMovement()) {
            this.xServer.getWinHandler().mouseEvent(1, (int) transformedPoint[0], (int) transformedPoint[1], 0);
        } else {
            this.xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
        }
        if (event.getPointerCount() == 1) {
            if (this.xServer.isRelativeMouseMovement()) {
                this.xServer.getWinHandler().mouseEvent(2, 0, 0, 0);
            } else {
                this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            }
        }
    }

    private void handleTouchMove(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
        if (this.xServer.isRelativeMouseMovement()) {
            this.xServer.getWinHandler().mouseEvent(1, (int) transformedPoint[0], (int) transformedPoint[1], 0);
        } else {
            this.xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
        }
    }

    private void handleTouchUp(MotionEvent event) {
        if (this.xServer.isRelativeMouseMovement()) {
            this.xServer.getWinHandler().mouseEvent(4, 0, 0, 0);
        } else {
            this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        }
    }

    private void handleTwoFingerScroll(MotionEvent event) {
        float scrollDistance = event.getY(0) - event.getY(1);
        if (Math.abs(scrollDistance) > 10.0f) {
            if (scrollDistance > 0.0f) {
                this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
            } else {
                this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
            }
        }
    }

    private void handleTwoFingerTap(MotionEvent event) {
        if (event.getPointerCount() == 2) {
            if (this.xServer.isRelativeMouseMovement()) {
                this.xServer.getWinHandler().mouseEvent(8, 0, 0, 0);
                this.xServer.getWinHandler().mouseEvent(16, 0, 0, 0);
            } else {
                this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
            }
        }
    }

    private void handleFingerUp(Finger finger1) {
        switch (this.numFingers) {
            case 1:
                if (this.simTouchScreen) {
                    Runnable clickDelay = new Runnable() { // from class: com.winlator.cmod.widget.TouchpadView$$ExternalSyntheticLambda1
                        @Override // java.lang.Runnable
                        public final void run() {
                            TouchpadView.this.lambda$handleFingerUp$1();
                        }
                    };
                    postDelayed(clickDelay, CLICK_DELAYED_TIME.byteValue());
                    break;
                } else if (finger1.isTap()) {
                    pressPointerButtonLeft(finger1);
                    break;
                }
                break;
            case 2:
                Finger finger2 = findSecondFinger(finger1);
                if (finger2 != null && finger1.isTap()) {
                    pressPointerButtonRight(finger1);
                    break;
                }
                break;
            case 4:
                if (this.fourFingersTapCallback != null) {
                    for (byte i = 0; i < 4; i = (byte) (i + 1)) {
                        if (this.fingers[i] != null && !this.fingers[i].isTap()) {
                            return;
                        }
                    }
                    this.fourFingersTapCallback.run();
                    break;
                }
                break;
        }
        releasePointerButtonLeft(finger1);
        releasePointerButtonRight(finger1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$handleFingerUp$1() {
        if (this.continueClick) {
            this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        }
    }

    private void handleFingerMove(Finger finger1) {
        boolean skipPointerMove = false;
        Finger finger2 = this.numFingers == 2 ? findSecondFinger(finger1) : null;
        if (finger2 != null) {
            float resolutionScale = 1000.0f / Math.min((int) this.xServer.screenInfo.width, (int) this.xServer.screenInfo.height);
            float currDistance = ((float) Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y)) * resolutionScale;
            if (currDistance < 350.0f) {
                this.scrollAccumY += ((finger1.y + finger2.y) * 0.5f) - ((finger1.lastY + finger2.lastY) * 0.5f);
                if (this.scrollAccumY < -100.0f) {
                    this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    this.scrollAccumY = 0.0f;
                } else if (this.scrollAccumY > 100.0f) {
                    this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    this.scrollAccumY = 0.0f;
                }
                this.scrolling = true;
            } else if (currDistance >= 350.0f && !this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) && finger2.travelDistance() < 10.0f) {
                pressPointerButtonLeft(finger1);
                skipPointerMove = true;
            }
        }
        if (!this.scrolling && this.numFingers <= 2 && !skipPointerMove) {
            int dx = finger1.deltaX();
            int dy = finger1.deltaY();
            if (this.simTouchScreen) {
                if (System.currentTimeMillis() - finger1.touchTime > CLICK_DELAYED_TIME.byteValue()) {
                    this.xServer.injectPointerMove(finger1.x, finger1.y);
                }
            } else if (this.xServer.isRelativeMouseMovement()) {
                WinHandler winHandler = this.xServer.getWinHandler();
                winHandler.mouseEvent(1, dx, dy, 0);
            } else {
                this.xServer.injectPointerMoveDelta(dx, dy);
            }
        }
    }

    private Finger findSecondFinger(Finger finger) {
        for (byte i = 0; i < 4; i = (byte) (i + 1)) {
            if (this.fingers[i] != null && this.fingers[i] != finger) {
                return this.fingers[i];
            }
        }
        return null;
    }

    private void pressPointerButtonLeft(Finger finger) {
        if (this.pointerButtonLeftEnabled && !this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            this.fingerPointerButtonLeft = finger;
        }
    }

    private void pressPointerButtonRight(Finger finger) {
        if (this.pointerButtonRightEnabled && !this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
            this.fingerPointerButtonRight = finger;
        }
    }

    private void releasePointerButtonLeft(Finger finger) {
        if (this.pointerButtonLeftEnabled && finger == this.fingerPointerButtonLeft && this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed(new Runnable() { // from class: com.winlator.cmod.widget.TouchpadView$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    TouchpadView.this.lambda$releasePointerButtonLeft$2();
                }
            }, 30L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$releasePointerButtonLeft$2() {
        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        this.fingerPointerButtonLeft = null;
    }

    private void releasePointerButtonRight(Finger finger) {
        if (this.pointerButtonRightEnabled && finger == this.fingerPointerButtonRight && this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            postDelayed(new Runnable() { // from class: com.winlator.cmod.widget.TouchpadView$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    TouchpadView.this.lambda$releasePointerButtonRight$3();
                }
            }, 30L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$releasePointerButtonRight$3() {
        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
        this.fingerPointerButtonRight = null;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public boolean isPointerButtonLeftEnabled() {
        return this.pointerButtonLeftEnabled;
    }

    public void setPointerButtonLeftEnabled(boolean pointerButtonLeftEnabled) {
        this.pointerButtonLeftEnabled = pointerButtonLeftEnabled;
    }

    public boolean isPointerButtonRightEnabled() {
        return this.pointerButtonRightEnabled;
    }

    public void setPointerButtonRightEnabled(boolean pointerButtonRightEnabled) {
        this.pointerButtonRightEnabled = pointerButtonRightEnabled;
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
    }

    public boolean onExternalMouseEvent(MotionEvent event) {
        if (!event.isFromSource(8194)) {
            return false;
        }
        int actionButton = event.getActionButton();
        switch (event.getAction()) {
            case 2:
            case 7:
                float[] transformedPoint = XForm.transformPoint(this.xform, event.getX(), event.getY());
                if (this.xServer.isRelativeMouseMovement()) {
                    this.xServer.getWinHandler().mouseEvent(1, (int) transformedPoint[0], (int) transformedPoint[1], 0);
                    break;
                } else {
                    this.xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
                    break;
                }
            case 8:
                float scrollY = event.getAxisValue(9);
                if (scrollY > -1.0f) {
                    if (scrollY >= 1.0f) {
                        if (this.xServer.isRelativeMouseMovement()) {
                            this.xServer.getWinHandler().mouseEvent(2048, 0, 0, (int) scrollY);
                            break;
                        } else {
                            this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                            this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                            break;
                        }
                    }
                } else if (this.xServer.isRelativeMouseMovement()) {
                    this.xServer.getWinHandler().mouseEvent(2048, 0, 0, (int) scrollY);
                    break;
                } else {
                    this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    break;
                }
                break;
            case 11:
                if (actionButton == 1) {
                    if (this.xServer.isRelativeMouseMovement()) {
                        this.xServer.getWinHandler().mouseEvent(2, 0, 0, 0);
                        break;
                    } else {
                        this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                        break;
                    }
                } else if (actionButton == 2) {
                    if (this.xServer.isRelativeMouseMovement()) {
                        this.xServer.getWinHandler().mouseEvent(8, 0, 0, 0);
                        break;
                    } else {
                        this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                        break;
                    }
                } else if (actionButton == 4) {
                    if (this.xServer.isRelativeMouseMovement()) {
                        this.xServer.getWinHandler().mouseEvent(32, 0, 0, 0);
                        break;
                    } else {
                        this.xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
                        break;
                    }
                }
                break;
            case 12:
                if (actionButton == 1) {
                    if (this.xServer.isRelativeMouseMovement()) {
                        this.xServer.getWinHandler().mouseEvent(4, 0, 0, 0);
                        break;
                    } else {
                        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                        break;
                    }
                } else if (actionButton == 2) {
                    if (this.xServer.isRelativeMouseMovement()) {
                        this.xServer.getWinHandler().mouseEvent(16, 0, 0, 0);
                        break;
                    } else {
                        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                        break;
                    }
                } else if (actionButton == 4) {
                    if (this.xServer.isRelativeMouseMovement()) {
                        this.xServer.getWinHandler().mouseEvent(64, 0, 0, 0);
                        break;
                    } else {
                        this.xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
                        break;
                    }
                }
                break;
        }
        return false;
    }

    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        float[] result = {0.0f, 0.0f};
        XForm.transformPoint(this.xform, lastX, lastY, result);
        float lastX2 = result[0];
        float lastY2 = result[1];
        XForm.transformPoint(this.xform, x, y, result);
        float x2 = result[0];
        float y2 = result[1];
        result[0] = x2 - lastX2;
        result[1] = y2 - lastY2;
        return result;
    }

    private StateListDrawable createTransparentBg() {
        StateListDrawable stateListDrawable = new StateListDrawable();
        ColorDrawable focusedDrawable = new ColorDrawable(0);
        ColorDrawable defaultDrawable = new ColorDrawable(0);
        stateListDrawable.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        stateListDrawable.addState(new int[0], defaultDrawable);
        return stateListDrawable;
    }

    public void setSimTouchScreen(boolean simTouchScreen) {
        this.simTouchScreen = simTouchScreen;
        this.xServer.setSimulateTouchScreen(this.simTouchScreen);
    }

    public boolean isSimTouchScreen() {
        return this.simTouchScreen;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$toggleFullscreen$4() {
        updateXform(getWidth(), getHeight(), this.xServer.screenInfo.width, this.xServer.screenInfo.height);
    }

    public void toggleFullscreen() {
        new Handler().postDelayed(new Runnable() { // from class: com.winlator.cmod.widget.TouchpadView$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                TouchpadView.this.lambda$toggleFullscreen$4();
            }
        }, 50L);
    }

    public void setMouseEnabled(boolean enabled) {
        this.mouseEnabled = enabled;
    }
}
