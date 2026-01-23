package com.winlator.input;

import android.annotation.SuppressLint;
import android.app.Activity; // USAR ISTO NO LUGAR DE X11ACTIVITY
import android.content.Context;
import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.winlator.LorieView;

public class TouchInputHandler {
    private final LorieView view;
    private final RenderData renderData = new RenderData();
    private final InputEventSender sender;
    private final GestureDetector gestureDetector;
    private final TapGestureDetector tapGestureDetector;
    private final SwipeDetector swipeDetector;
    private InputStrategyInterface inputStrategy;
    
    // Substituindo a dependência da X11Activity
    private final Activity mActivity; 

    public TouchInputHandler(Activity activity, LorieView view) {
        this.mActivity = activity;
        this.view = view;
        this.sender = new InputEventSender(new InputStubWrapper(view));
        
        // Inicializa detectores
        Context context = view.getContext();
        this.swipeDetector = new SwipeDetector(context);
        this.tapGestureDetector = new TapGestureDetector(context, new TapGestureDetector.OnTapListener() {
            @Override
            public void onTap(int pointerCount, float x, float y) {
                if (inputStrategy != null) inputStrategy.onTap(InputStub.BUTTON_LEFT);
            }
            @Override
            public void onLongPress(int pointerCount, float x, float y) {
                if (inputStrategy != null) inputStrategy.onPressAndHold(InputStub.BUTTON_LEFT, true);
            }
        });
        
        // Estratégia padrão: Trackpad
        this.inputStrategy = new InputStrategyInterface.TrackpadInputStrategy(sender);
        
        this.gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (inputStrategy != null) {
                    inputStrategy.onScroll(distanceX, distanceY);
                    return true;
                }
                return false;
            }
        });
    }

    public void handleTouchEvent(View v, View xView, MotionEvent event) {
        // Atualiza dimensões da tela
        renderData.screenWidth = v.getWidth();
        renderData.screenHeight = v.getHeight();

        // Passa pelos detectores
        if (tapGestureDetector.onTouchEvent(event)) return;
        if (gestureDetector.onTouchEvent(event)) return;
        
        // Passa para a estratégia (Trackpad/Touchscreen)
        if (inputStrategy != null) {
            inputStrategy.onMotionEvent(event);
        }
    }
    
    // Wrapper para conectar o InputStub do Java ao LorieView nativo
    private static class InputStubWrapper implements InputStub {
        private final LorieView view;
        
        InputStubWrapper(LorieView view) { this.view = view; }

        @Override
        public void sendMouseEvent(float x, float y, int whichButton, boolean buttonDown, boolean relative) {
            view.sendMouseEvent(x, y, whichButton, buttonDown, relative);
        }

        @Override
        public void sendMouseWheelEvent(float deltaX, float deltaY) {
            if (deltaY > 0) view.sendMouseEvent(0, 0, InputStub.BUTTON_SCROLL_DOWN, true, true);
            else if (deltaY < 0) view.sendMouseEvent(0, 0, InputStub.BUTTON_SCROLL_UP, true, true);
            
             if (deltaY > 0) view.sendMouseEvent(0, 0, InputStub.BUTTON_SCROLL_DOWN, false, true);
            else if (deltaY < 0) view.sendMouseEvent(0, 0, InputStub.BUTTON_SCROLL_UP, false, true);
        }

        @Override
        public boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown) {
            return view.sendKeyEvent(scanCode, keyCode, keyDown);
        }

        @Override
        public void sendTextEvent(byte[] utf8Bytes) {
            view.sendTextEvent(utf8Bytes);
        }

        @Override
        public void sendTouchEvent(int action, int pointerId, int x, int y) {
            view.sendTouchEvent(action, pointerId, x, y);
        }

        @Override
        public void sendStylusEvent(float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouseMode) {
            view.sendStylusEvent(x, y, pressure, tiltX, tiltY, orientation, buttons, eraser, mouseMode);
        }
    }
    
    public void sendMouseEvent(float x, float y, int button, boolean down, boolean relative) {
        view.sendMouseEvent(x, y, button, down, relative);
    }
}