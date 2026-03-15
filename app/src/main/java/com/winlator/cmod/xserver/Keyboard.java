package com.winlator.cmod.xserver;

import android.view.KeyEvent;
import androidx.collection.ArraySet;
import com.winlator.cmod.inputcontrols.ExternalController;
import java.util.ArrayList;
import jp.kshoji.javax.sound.midi.ShortMessage;

/* loaded from: classes11.dex */
public class Keyboard {
    public static final byte KEYSYMS_PER_KEYCODE = 2;
    public static final short KEYS_COUNT = 248;
    public static final short MAX_KEYCODE = 255;
    public static final short MIN_KEYCODE = 8;
    private final XServer xServer;
    public final int[] keysyms = new int[ShortMessage.TIMING_CLOCK];
    private final Bitmask modifiersMask = new Bitmask();
    private final XKeycode[] keycodeMap = createKeycodeMap();
    private final ArraySet<Byte> pressedKeys = new ArraySet<>();
    private final ArrayList<OnKeyboardListener> onKeyboardListeners = new ArrayList<>();

    public interface OnKeyboardListener {
        void onKeyPress(byte b, int i);

        void onKeyRelease(byte b);
    }

    public Keyboard(XServer xServer) {
        this.xServer = xServer;
    }

    public Bitmask getModifiersMask() {
        return this.modifiersMask;
    }

    public void setKeysyms(byte keycode, int minKeysym, int majKeysym) {
        int index = keycode - 8;
        this.keysyms[(index * 2) + 0] = minKeysym;
        this.keysyms[(index * 2) + 1] = majKeysym;
    }

    public boolean hasKeysym(byte keycode, int keysym) {
        int index = keycode - 8;
        return this.keysyms[(index * 2) + 0] == keysym || this.keysyms[(index * 2) + 1] == keysym;
    }

    public void setKeyPress(byte keycode, int keysym) {
        if (isModifierSticky(keycode)) {
            if (this.pressedKeys.contains(Byte.valueOf(keycode))) {
                this.pressedKeys.remove(Byte.valueOf(keycode));
                this.modifiersMask.unset(getModifierFlag(keycode));
                triggerOnKeyRelease(keycode);
                return;
            } else {
                this.pressedKeys.add(Byte.valueOf(keycode));
                this.modifiersMask.set(getModifierFlag(keycode));
                triggerOnKeyPress(keycode, keysym);
                return;
            }
        }
        if (!this.pressedKeys.contains(Byte.valueOf(keycode))) {
            this.pressedKeys.add(Byte.valueOf(keycode));
            if (isModifier(keycode)) {
                this.modifiersMask.set(getModifierFlag(keycode));
            }
            triggerOnKeyPress(keycode, keysym);
        }
    }

    public void setKeyRelease(byte keycode) {
        if (!isModifierSticky(keycode) && this.pressedKeys.contains(Byte.valueOf(keycode))) {
            this.pressedKeys.remove(Byte.valueOf(keycode));
            if (isModifier(keycode)) {
                this.modifiersMask.unset(getModifierFlag(keycode));
            }
            triggerOnKeyRelease(keycode);
        }
    }

    public void addOnKeyboardListener(OnKeyboardListener onKeyboardListener) {
        this.onKeyboardListeners.add(onKeyboardListener);
    }

    public void removeOnKeyboardListener(OnKeyboardListener onKeyboardListener) {
        this.onKeyboardListeners.remove(onKeyboardListener);
    }

    private void triggerOnKeyPress(byte keycode, int keysym) {
        for (int i = this.onKeyboardListeners.size() - 1; i >= 0; i--) {
            this.onKeyboardListeners.get(i).onKeyPress(keycode, keysym);
        }
    }

    private void triggerOnKeyRelease(byte keycode) {
        for (int i = this.onKeyboardListeners.size() - 1; i >= 0; i--) {
            this.onKeyboardListeners.get(i).onKeyRelease(keycode);
        }
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (ExternalController.isGameController(event.getDevice())) {
            return false;
        }
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        if (keyCode < 0 || keyCode >= this.keycodeMap.length) {
            return false;
        }
        if (keyCode == 61 || keyCode == 111) {
            if (action == 0) {
                this.xServer.injectKeyPress(this.keycodeMap[keyCode]);
                return true;
            }
            if (action == 1) {
                this.xServer.injectKeyRelease(this.keycodeMap[keyCode]);
                return true;
            }
        } else if (action == 0 || action == 1) {
            XKeycode xKeycode = this.keycodeMap[keyCode];
            if (xKeycode == null) {
                return false;
            }
            if (action == 0) {
                boolean shiftPressed = event.isShiftPressed() || keyCode == 77 || keyCode == 17 || keyCode == 18 || keyCode == 81;
                if (shiftPressed) {
                    this.xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
                }
                this.xServer.injectKeyPress(xKeycode, xKeycode != XKeycode.KEY_ENTER ? event.getUnicodeChar() : 0);
            } else if (action == 1) {
                this.xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
                this.xServer.injectKeyRelease(xKeycode);
            }
        }
        return true;
    }

    private static XKeycode[] createKeycodeMap() {
        XKeycode[] keycodeMap = new XKeycode[KeyEvent.getMaxKeyCode() + 1];
        keycodeMap[111] = XKeycode.KEY_ESC;
        keycodeMap[66] = XKeycode.KEY_ENTER;
        keycodeMap[21] = XKeycode.KEY_LEFT;
        keycodeMap[22] = XKeycode.KEY_RIGHT;
        keycodeMap[19] = XKeycode.KEY_UP;
        keycodeMap[20] = XKeycode.KEY_DOWN;
        keycodeMap[67] = XKeycode.KEY_BKSP;
        keycodeMap[124] = XKeycode.KEY_INSERT;
        keycodeMap[112] = XKeycode.KEY_DEL;
        keycodeMap[122] = XKeycode.KEY_HOME;
        keycodeMap[123] = XKeycode.KEY_END;
        keycodeMap[92] = XKeycode.KEY_PRIOR;
        keycodeMap[93] = XKeycode.KEY_NEXT;
        keycodeMap[59] = XKeycode.KEY_SHIFT_L;
        keycodeMap[60] = XKeycode.KEY_SHIFT_R;
        keycodeMap[113] = XKeycode.KEY_CTRL_L;
        keycodeMap[114] = XKeycode.KEY_CTRL_R;
        keycodeMap[57] = XKeycode.KEY_ALT_L;
        keycodeMap[58] = XKeycode.KEY_ALT_R;
        keycodeMap[61] = XKeycode.KEY_TAB;
        keycodeMap[62] = XKeycode.KEY_SPACE;
        keycodeMap[29] = XKeycode.KEY_A;
        keycodeMap[30] = XKeycode.KEY_B;
        keycodeMap[31] = XKeycode.KEY_C;
        keycodeMap[32] = XKeycode.KEY_D;
        keycodeMap[33] = XKeycode.KEY_E;
        keycodeMap[34] = XKeycode.KEY_F;
        keycodeMap[35] = XKeycode.KEY_G;
        keycodeMap[36] = XKeycode.KEY_H;
        keycodeMap[37] = XKeycode.KEY_I;
        keycodeMap[38] = XKeycode.KEY_J;
        keycodeMap[39] = XKeycode.KEY_K;
        keycodeMap[40] = XKeycode.KEY_L;
        keycodeMap[41] = XKeycode.KEY_M;
        keycodeMap[42] = XKeycode.KEY_N;
        keycodeMap[43] = XKeycode.KEY_O;
        keycodeMap[44] = XKeycode.KEY_P;
        keycodeMap[45] = XKeycode.KEY_Q;
        keycodeMap[46] = XKeycode.KEY_R;
        keycodeMap[47] = XKeycode.KEY_S;
        keycodeMap[48] = XKeycode.KEY_T;
        keycodeMap[49] = XKeycode.KEY_U;
        keycodeMap[50] = XKeycode.KEY_V;
        keycodeMap[51] = XKeycode.KEY_W;
        keycodeMap[52] = XKeycode.KEY_X;
        keycodeMap[53] = XKeycode.KEY_Y;
        keycodeMap[54] = XKeycode.KEY_Z;
        keycodeMap[7] = XKeycode.KEY_0;
        keycodeMap[8] = XKeycode.KEY_1;
        keycodeMap[9] = XKeycode.KEY_2;
        keycodeMap[10] = XKeycode.KEY_3;
        keycodeMap[11] = XKeycode.KEY_4;
        keycodeMap[12] = XKeycode.KEY_5;
        keycodeMap[13] = XKeycode.KEY_6;
        keycodeMap[14] = XKeycode.KEY_7;
        keycodeMap[15] = XKeycode.KEY_8;
        keycodeMap[16] = XKeycode.KEY_9;
        keycodeMap[17] = XKeycode.KEY_8;
        keycodeMap[18] = XKeycode.KEY_3;
        keycodeMap[55] = XKeycode.KEY_COMMA;
        keycodeMap[56] = XKeycode.KEY_PERIOD;
        keycodeMap[74] = XKeycode.KEY_SEMICOLON;
        keycodeMap[75] = XKeycode.KEY_APOSTROPHE;
        keycodeMap[71] = XKeycode.KEY_BRACKET_LEFT;
        keycodeMap[72] = XKeycode.KEY_BRACKET_RIGHT;
        keycodeMap[68] = XKeycode.KEY_GRAVE;
        keycodeMap[69] = XKeycode.KEY_MINUS;
        keycodeMap[81] = XKeycode.KEY_EQUAL;
        keycodeMap[70] = XKeycode.KEY_EQUAL;
        keycodeMap[76] = XKeycode.KEY_SLASH;
        keycodeMap[77] = XKeycode.KEY_2;
        keycodeMap[73] = XKeycode.KEY_BACKSLASH;
        keycodeMap[154] = XKeycode.KEY_KP_DIVIDE;
        keycodeMap[155] = XKeycode.KEY_KP_MULTIPLY;
        keycodeMap[156] = XKeycode.KEY_KP_SUBTRACT;
        keycodeMap[157] = XKeycode.KEY_KP_ADD;
        keycodeMap[158] = XKeycode.KEY_KP_DEL;
        keycodeMap[144] = XKeycode.KEY_KP_0;
        keycodeMap[145] = XKeycode.KEY_KP_1;
        keycodeMap[146] = XKeycode.KEY_KP_2;
        keycodeMap[147] = XKeycode.KEY_KP_3;
        keycodeMap[148] = XKeycode.KEY_KP_4;
        keycodeMap[149] = XKeycode.KEY_KP_5;
        keycodeMap[150] = XKeycode.KEY_KP_6;
        keycodeMap[151] = XKeycode.KEY_KP_7;
        keycodeMap[152] = XKeycode.KEY_KP_8;
        keycodeMap[153] = XKeycode.KEY_KP_9;
        keycodeMap[131] = XKeycode.KEY_F1;
        keycodeMap[132] = XKeycode.KEY_F2;
        keycodeMap[133] = XKeycode.KEY_F3;
        keycodeMap[134] = XKeycode.KEY_F4;
        keycodeMap[135] = XKeycode.KEY_F5;
        keycodeMap[136] = XKeycode.KEY_F6;
        keycodeMap[137] = XKeycode.KEY_F7;
        keycodeMap[138] = XKeycode.KEY_F8;
        keycodeMap[139] = XKeycode.KEY_F9;
        keycodeMap[140] = XKeycode.KEY_F10;
        keycodeMap[141] = XKeycode.KEY_F11;
        keycodeMap[142] = XKeycode.KEY_F12;
        keycodeMap[143] = XKeycode.KEY_NUM_LOCK;
        keycodeMap[115] = XKeycode.KEY_CAPS_LOCK;
        return keycodeMap;
    }

    public static Keyboard createKeyboard(XServer xServer) {
        Keyboard keyboard = new Keyboard(xServer);
        keyboard.setKeysyms(XKeycode.KEY_ESC.id, 65307, 0);
        keyboard.setKeysyms(XKeycode.KEY_ENTER.id, 65293, 0);
        keyboard.setKeysyms(XKeycode.KEY_RIGHT.id, 65363, 0);
        keyboard.setKeysyms(XKeycode.KEY_UP.id, 65362, 0);
        keyboard.setKeysyms(XKeycode.KEY_LEFT.id, 65361, 0);
        keyboard.setKeysyms(XKeycode.KEY_DOWN.id, 65364, 0);
        keyboard.setKeysyms(XKeycode.KEY_DEL.id, 65535, 0);
        keyboard.setKeysyms(XKeycode.KEY_BKSP.id, 65288, 0);
        keyboard.setKeysyms(XKeycode.KEY_INSERT.id, 65379, 0);
        keyboard.setKeysyms(XKeycode.KEY_PRIOR.id, 65365, 0);
        keyboard.setKeysyms(XKeycode.KEY_NEXT.id, 65366, 0);
        keyboard.setKeysyms(XKeycode.KEY_HOME.id, 65360, 0);
        keyboard.setKeysyms(XKeycode.KEY_END.id, 65367, 0);
        keyboard.setKeysyms(XKeycode.KEY_SHIFT_L.id, 65505, 0);
        keyboard.setKeysyms(XKeycode.KEY_SHIFT_R.id, 65506, 0);
        keyboard.setKeysyms(XKeycode.KEY_CTRL_L.id, 65507, 0);
        keyboard.setKeysyms(XKeycode.KEY_CTRL_R.id, 65508, 0);
        keyboard.setKeysyms(XKeycode.KEY_ALT_L.id, 65511, 0);
        keyboard.setKeysyms(XKeycode.KEY_ALT_R.id, 65512, 0);
        keyboard.setKeysyms(XKeycode.KEY_TAB.id, 65289, 0);
        keyboard.setKeysyms(XKeycode.KEY_SPACE.id, 32, 32);
        keyboard.setKeysyms(XKeycode.KEY_A.id, 97, 65);
        keyboard.setKeysyms(XKeycode.KEY_B.id, 98, 66);
        keyboard.setKeysyms(XKeycode.KEY_C.id, 99, 67);
        keyboard.setKeysyms(XKeycode.KEY_D.id, 100, 68);
        keyboard.setKeysyms(XKeycode.KEY_E.id, 101, 69);
        keyboard.setKeysyms(XKeycode.KEY_F.id, 102, 70);
        keyboard.setKeysyms(XKeycode.KEY_G.id, 103, 71);
        keyboard.setKeysyms(XKeycode.KEY_H.id, 104, 72);
        keyboard.setKeysyms(XKeycode.KEY_I.id, 105, 73);
        keyboard.setKeysyms(XKeycode.KEY_J.id, 106, 74);
        keyboard.setKeysyms(XKeycode.KEY_K.id, 107, 75);
        keyboard.setKeysyms(XKeycode.KEY_L.id, 108, 76);
        keyboard.setKeysyms(XKeycode.KEY_M.id, 109, 77);
        keyboard.setKeysyms(XKeycode.KEY_N.id, 110, 78);
        keyboard.setKeysyms(XKeycode.KEY_O.id, 111, 79);
        keyboard.setKeysyms(XKeycode.KEY_P.id, 112, 80);
        keyboard.setKeysyms(XKeycode.KEY_Q.id, 113, 81);
        keyboard.setKeysyms(XKeycode.KEY_R.id, 114, 82);
        keyboard.setKeysyms(XKeycode.KEY_S.id, 115, 83);
        keyboard.setKeysyms(XKeycode.KEY_T.id, 116, 84);
        keyboard.setKeysyms(XKeycode.KEY_U.id, 117, 85);
        keyboard.setKeysyms(XKeycode.KEY_V.id, 118, 86);
        keyboard.setKeysyms(XKeycode.KEY_W.id, 119, 87);
        keyboard.setKeysyms(XKeycode.KEY_X.id, 120, 88);
        keyboard.setKeysyms(XKeycode.KEY_Y.id, 121, 89);
        keyboard.setKeysyms(XKeycode.KEY_Z.id, 122, 90);
        keyboard.setKeysyms(XKeycode.KEY_1.id, 49, 33);
        keyboard.setKeysyms(XKeycode.KEY_2.id, 50, 64);
        keyboard.setKeysyms(XKeycode.KEY_3.id, 51, 35);
        keyboard.setKeysyms(XKeycode.KEY_4.id, 52, 36);
        keyboard.setKeysyms(XKeycode.KEY_5.id, 53, 37);
        keyboard.setKeysyms(XKeycode.KEY_6.id, 54, 94);
        keyboard.setKeysyms(XKeycode.KEY_7.id, 55, 38);
        keyboard.setKeysyms(XKeycode.KEY_8.id, 56, 42);
        keyboard.setKeysyms(XKeycode.KEY_9.id, 57, 40);
        keyboard.setKeysyms(XKeycode.KEY_0.id, 48, 41);
        keyboard.setKeysyms(XKeycode.KEY_COMMA.id, 44, 60);
        keyboard.setKeysyms(XKeycode.KEY_PERIOD.id, 46, 62);
        keyboard.setKeysyms(XKeycode.KEY_SEMICOLON.id, 59, 58);
        keyboard.setKeysyms(XKeycode.KEY_APOSTROPHE.id, 39, 34);
        keyboard.setKeysyms(XKeycode.KEY_BRACKET_LEFT.id, 91, 123);
        keyboard.setKeysyms(XKeycode.KEY_BRACKET_RIGHT.id, 93, 125);
        keyboard.setKeysyms(XKeycode.KEY_GRAVE.id, 96, 126);
        keyboard.setKeysyms(XKeycode.KEY_MINUS.id, 45, 95);
        keyboard.setKeysyms(XKeycode.KEY_EQUAL.id, 61, 43);
        keyboard.setKeysyms(XKeycode.KEY_SLASH.id, 47, 63);
        keyboard.setKeysyms(XKeycode.KEY_BACKSLASH.id, 92, 124);
        keyboard.setKeysyms(XKeycode.KEY_KP_DIVIDE.id, 65455, 65455);
        keyboard.setKeysyms(XKeycode.KEY_KP_MULTIPLY.id, 65450, 65450);
        keyboard.setKeysyms(XKeycode.KEY_KP_SUBTRACT.id, 65453, 65453);
        keyboard.setKeysyms(XKeycode.KEY_KP_ADD.id, 65451, 65451);
        keyboard.setKeysyms(XKeycode.KEY_KP_0.id, 65456, 65438);
        keyboard.setKeysyms(XKeycode.KEY_KP_1.id, 65457, 65436);
        keyboard.setKeysyms(XKeycode.KEY_KP_2.id, 65458, 65433);
        keyboard.setKeysyms(XKeycode.KEY_KP_3.id, 65459, 65459);
        keyboard.setKeysyms(XKeycode.KEY_KP_4.id, 65460, 65430);
        keyboard.setKeysyms(XKeycode.KEY_KP_5.id, 65461, 65461);
        keyboard.setKeysyms(XKeycode.KEY_KP_6.id, 65462, 65432);
        keyboard.setKeysyms(XKeycode.KEY_KP_7.id, 65463, 65429);
        keyboard.setKeysyms(XKeycode.KEY_KP_8.id, 65464, 65431);
        keyboard.setKeysyms(XKeycode.KEY_KP_9.id, 65465, 65465);
        keyboard.setKeysyms(XKeycode.KEY_KP_DEL.id, 65439, 0);
        keyboard.setKeysyms(XKeycode.KEY_F1.id, 65470, 0);
        keyboard.setKeysyms(XKeycode.KEY_F2.id, 65471, 0);
        keyboard.setKeysyms(XKeycode.KEY_F3.id, 65472, 0);
        keyboard.setKeysyms(XKeycode.KEY_F4.id, 65473, 0);
        keyboard.setKeysyms(XKeycode.KEY_F5.id, 65474, 0);
        keyboard.setKeysyms(XKeycode.KEY_F6.id, 65475, 0);
        keyboard.setKeysyms(XKeycode.KEY_F7.id, 65476, 0);
        keyboard.setKeysyms(XKeycode.KEY_F8.id, 65477, 0);
        keyboard.setKeysyms(XKeycode.KEY_F9.id, 65478, 0);
        keyboard.setKeysyms(XKeycode.KEY_F10.id, 65479, 0);
        keyboard.setKeysyms(XKeycode.KEY_F11.id, 65480, 0);
        keyboard.setKeysyms(XKeycode.KEY_F12.id, 65481, 0);
        return keyboard;
    }

    public static boolean isModifier(byte keycode) {
        return keycode == XKeycode.KEY_SHIFT_L.id || keycode == XKeycode.KEY_SHIFT_R.id || keycode == XKeycode.KEY_CTRL_L.id || keycode == XKeycode.KEY_CTRL_R.id || keycode == XKeycode.KEY_ALT_L.id || keycode == XKeycode.KEY_ALT_R.id || keycode == XKeycode.KEY_CAPS_LOCK.id || keycode == XKeycode.KEY_NUM_LOCK.id;
    }

    public static int getModifierFlag(byte keycode) {
        if (keycode == XKeycode.KEY_SHIFT_L.id || keycode == XKeycode.KEY_SHIFT_R.id) {
            return 1;
        }
        if (keycode == XKeycode.KEY_CAPS_LOCK.id) {
            return 2;
        }
        if (keycode == XKeycode.KEY_CTRL_L.id || keycode == XKeycode.KEY_CTRL_R.id) {
            return 4;
        }
        if (keycode == XKeycode.KEY_ALT_L.id || keycode == XKeycode.KEY_ALT_R.id) {
            return 8;
        }
        if (keycode == XKeycode.KEY_NUM_LOCK.id) {
            return 16;
        }
        return 0;
    }

    public static boolean isModifierSticky(byte keycode) {
        return keycode == XKeycode.KEY_CAPS_LOCK.id || keycode == XKeycode.KEY_NUM_LOCK.id;
    }
}
