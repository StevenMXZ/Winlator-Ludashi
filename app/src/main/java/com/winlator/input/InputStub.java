package com.winlator.input;

public interface InputStub {
    int BUTTON_UNDEFINED = 0;
    int BUTTON_LEFT = 1;
    int BUTTON_MIDDLE = 2;
    int BUTTON_RIGHT = 3;
    int BUTTON_SCROLL = 4;
    int BUTTON_SCROLL_UP = 4;
    int BUTTON_SCROLL_DOWN = 5;

    void sendMouseEvent(float x, float y, int whichButton, boolean buttonDown, boolean relative);
    void sendMouseWheelEvent(float deltaX, float deltaY);
    boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown);
    void sendTextEvent(byte[] utf8Bytes);
    void sendTouchEvent(int action, int pointerId, int x, int y);
    void sendStylusEvent(float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouseMode);
}