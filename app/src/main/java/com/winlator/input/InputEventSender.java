package com.winlator.input;

import android.view.KeyEvent;
import java.util.TreeSet;

public final class InputEventSender {
    private final InputStub mInjector;
    public boolean tapToMove = false;
    
    // CORREÇÃO: Variável local em vez de importar do X11Activity
    private boolean externalKeyboardConnected = false; 

    private final TreeSet<Integer> mPressedTextKeys;
    private final TreeSet<Integer> mPressedKeys;

    public InputEventSender(InputStub injector) {
        if (injector == null)
            throw new NullPointerException();
        mInjector = injector;
        mPressedTextKeys = new TreeSet<>();
        mPressedKeys = new TreeSet<>();
    }

    public void sendMouseEvent(float x, float y, int whichButton, boolean buttonDown, boolean relative) {
        mInjector.sendMouseEvent(x, y, whichButton, buttonDown, relative);
    }
    
    public void sendMouseDown(int whichButton, boolean relative) {
        mInjector.sendMouseEvent(0, 0, whichButton, true, relative);
    }

    public void sendMouseUp(int whichButton, boolean relative) {
        mInjector.sendMouseEvent(0, 0, whichButton, false, relative);
    }

    public void sendMouseClick(int whichButton, boolean relative) {
        sendMouseDown(whichButton, relative);
        sendMouseUp(whichButton, relative);
    }

    public void sendMouseWheelEvent(float deltaX, float deltaY) {
        mInjector.sendMouseWheelEvent(deltaX, deltaY);
    }
    
    public void sendCursorMove(float x, float y, boolean relative) {
        mInjector.sendMouseEvent(x, y, InputStub.BUTTON_UNDEFINED, false, relative);
    }
}