package com.winlator.cmod.stream.moonlight;

import android.util.Log;
import android.util.SparseArray;

import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XServer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Turns Moonlight input packets (moonlight-common-c Input.h) into Winlator input:
 * keyboard/mouse into the X server, gamepads into WinHandler controller slots.
 */
public final class MoonlightInput {
    private static final String TAG = "LudashiMoonlight";

    private static final int KEY_DOWN = 0x03, KEY_UP = 0x04;
    private static final int MOUSE_ABS = 0x05, MOUSE_REL = 0x06, MOUSE_REL_GEN5 = 0x07;
    private static final int MOUSE_DOWN_GEN5 = 0x08, MOUSE_UP_GEN5 = 0x09;
    private static final int SCROLL = 0x09, SCROLL_GEN5 = 0x0A;
    private static final int CONTROLLER = 0x0A, MULTI_CONTROLLER = 0x0D, MULTI_CONTROLLER_GEN5 = 0x0C;
    private static final int HAPTICS = 0x0D;
    private static final int SS_HSCROLL = 0x55000001, SS_CONTROLLER_ARRIVAL = 0x55000004;
    private static final int UTF8_TEXT = 0x17;
    // Windows SendInput flags for the side buttons (X1 = back, X2 = forward).
    private static final int MOUSEEVENTF_XDOWN = 0x0080, MOUSEEVENTF_XUP = 0x0100;

    private final XServer xServer;
    private final WinHandler winHandler;
    private final SparseArray<GamepadState> pads = new SparseArray<>();
    private int activeMask;
    private int scrollAccum;
    private final XKeycode[] vkMap = new XKeycode[256];

    public MoonlightInput(XServer xServer, WinHandler winHandler) {
        this.xServer = xServer;
        this.winHandler = winHandler;
        buildVkMap();
    }

    /** data[off..off+len) starts with NV_INPUT_HEADER (size BE32, magic LE32). */
    public void handle(byte[] data, int off, int len) {
        if (len < 8) return;
        ByteBuffer be = ByteBuffer.wrap(data, off, len).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer le = ByteBuffer.wrap(data, off, len).order(ByteOrder.LITTLE_ENDIAN);
        int magic = le.getInt(off + 4);
        int p = off + 8; // payload start
        try {
            // Mouse button / scroll / multi-controller share low magic values across generations;
            // the generation-5 values are what current Moonlight clients send.
            switch (magic) {
                case KEY_DOWN:
                case KEY_UP:
                    key(le.getShort(p + 1) & 0xFF, magic == KEY_DOWN);
                    break;
                case MOUSE_REL:
                case MOUSE_REL_GEN5:
                    if (xServer != null) xServer.injectPointerMoveDelta(be.getShort(p), be.getShort(p + 2));
                    break;
                case MOUSE_ABS: {
                    if (xServer == null) break;
                    int x = be.getShort(p), y = be.getShort(p + 2);
                    int w = (be.getShort(p + 6) & 0xFFFF) + 1, h = (be.getShort(p + 8) & 0xFFFF) + 1;
                    int sx = (int) ((long) x * xServer.screenInfo.width / Math.max(1, w));
                    int sy = (int) ((long) y * xServer.screenInfo.height / Math.max(1, h));
                    xServer.injectPointerMove(sx, sy);
                    break;
                }
                case MOUSE_DOWN_GEN5:
                case MOUSE_UP_GEN5:
                    mouseButton(data[p] & 0xFF, magic == MOUSE_DOWN_GEN5);
                    break;
                case SCROLL_GEN5:
                    scroll(be.getShort(p));
                    break;
                case MULTI_CONTROLLER_GEN5: // 0x0D is the haptics-enable packet on current clients
                    multiController(le, p);
                    break;
                case HAPTICS:
                    break;
                case UTF8_TEXT: {
                    int textLen = be.getInt(off) - 4; // header size counts the magic
                    if (textLen > 0 && p + textLen <= off + len)
                        typeText(new String(data, p, textLen, java.nio.charset.StandardCharsets.UTF_8));
                    break;
                }
                case SS_HSCROLL:
                case SS_CONTROLLER_ARRIVAL:
                    break;
                default:
                    break;
            }
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "short input packet, magic " + Integer.toHexString(magic));
        }
    }

    public void release() {
        for (int i = 0; i < pads.size(); i++) winHandler.releaseRemoteGamepad(pads.keyAt(i));
        pads.clear();
    }

    // ---- keyboard ----

    private void key(int vk, boolean down) {
        if (xServer == null) return;
        XKeycode k = vkMap[vk];
        if (k == null) return;
        if (down) xServer.injectKeyPress(k);
        else xServer.injectKeyRelease(k);
    }

    private void buildVkMap() {
        vkMap[0x08] = XKeycode.KEY_BKSP; vkMap[0x09] = XKeycode.KEY_TAB; vkMap[0x0D] = XKeycode.KEY_ENTER;
        vkMap[0x10] = XKeycode.KEY_SHIFT_L; vkMap[0x11] = XKeycode.KEY_CTRL_L; vkMap[0x12] = XKeycode.KEY_ALT_L;
        vkMap[0x14] = XKeycode.KEY_CAPS_LOCK; vkMap[0x1B] = XKeycode.KEY_ESC; vkMap[0x20] = XKeycode.KEY_SPACE;
        vkMap[0x21] = XKeycode.KEY_PRIOR; vkMap[0x22] = XKeycode.KEY_NEXT; vkMap[0x23] = XKeycode.KEY_END;
        vkMap[0x24] = XKeycode.KEY_HOME; vkMap[0x25] = XKeycode.KEY_LEFT; vkMap[0x26] = XKeycode.KEY_UP;
        vkMap[0x27] = XKeycode.KEY_RIGHT; vkMap[0x28] = XKeycode.KEY_DOWN; vkMap[0x2C] = XKeycode.KEY_PRTSCN;
        vkMap[0x2D] = XKeycode.KEY_INSERT; vkMap[0x2E] = XKeycode.KEY_DEL;
        XKeycode[] digits = {XKeycode.KEY_0, XKeycode.KEY_1, XKeycode.KEY_2, XKeycode.KEY_3, XKeycode.KEY_4,
                XKeycode.KEY_5, XKeycode.KEY_6, XKeycode.KEY_7, XKeycode.KEY_8, XKeycode.KEY_9};
        for (int i = 0; i < 10; i++) vkMap[0x30 + i] = digits[i];
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (int i = 0; i < 26; i++) vkMap[0x41 + i] = XKeycode.valueOf("KEY_" + letters.charAt(i));
        XKeycode[] kp = {XKeycode.KEY_KP_0, XKeycode.KEY_KP_1, XKeycode.KEY_KP_2, XKeycode.KEY_KP_3, XKeycode.KEY_KP_4,
                XKeycode.KEY_KP_5, XKeycode.KEY_KP_6, XKeycode.KEY_KP_7, XKeycode.KEY_KP_8, XKeycode.KEY_KP_9};
        for (int i = 0; i < 10; i++) vkMap[0x60 + i] = kp[i];
        vkMap[0x6A] = XKeycode.KEY_KP_MULTIPLY; vkMap[0x6B] = XKeycode.KEY_KP_ADD;
        vkMap[0x6D] = XKeycode.KEY_KP_SUBTRACT; vkMap[0x6E] = XKeycode.KEY_KP_DEL; vkMap[0x6F] = XKeycode.KEY_KP_DIVIDE;
        XKeycode[] f = {XKeycode.KEY_F1, XKeycode.KEY_F2, XKeycode.KEY_F3, XKeycode.KEY_F4, XKeycode.KEY_F5, XKeycode.KEY_F6,
                XKeycode.KEY_F7, XKeycode.KEY_F8, XKeycode.KEY_F9, XKeycode.KEY_F10, XKeycode.KEY_F11, XKeycode.KEY_F12};
        for (int i = 0; i < 12; i++) vkMap[0x70 + i] = f[i];
        vkMap[0x90] = XKeycode.KEY_NUM_LOCK; vkMap[0x91] = XKeycode.KEY_SCROLL_LOCK;
        vkMap[0xA0] = XKeycode.KEY_SHIFT_L; vkMap[0xA1] = XKeycode.KEY_SHIFT_R;
        vkMap[0xA2] = XKeycode.KEY_CTRL_L; vkMap[0xA3] = XKeycode.KEY_CTRL_R;
        vkMap[0xA4] = XKeycode.KEY_ALT_L; vkMap[0xA5] = XKeycode.KEY_ALT_R;
        vkMap[0xBA] = XKeycode.KEY_SEMICOLON; vkMap[0xBB] = XKeycode.KEY_EQUAL; vkMap[0xBC] = XKeycode.KEY_COMMA;
        vkMap[0xBD] = XKeycode.KEY_MINUS; vkMap[0xBE] = XKeycode.KEY_PERIOD; vkMap[0xBF] = XKeycode.KEY_SLASH;
        vkMap[0xC0] = XKeycode.KEY_GRAVE; vkMap[0xDB] = XKeycode.KEY_BRACKET_LEFT; vkMap[0xDC] = XKeycode.KEY_BACKSLASH;
        vkMap[0xDD] = XKeycode.KEY_BRACKET_RIGHT; vkMap[0xDE] = XKeycode.KEY_APOSTROPHE;
    }

    // ---- text (Moonlight on-screen keyboard / paste) ----

    private static final String SHIFTED = "~!@#$%^&*()_+{}|:\"<>?";
    private static final String UNSHIFTED = "`1234567890-=[]\\;',./";

    private void typeText(String text) {
        if (xServer == null) return;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            typeChar(cp);
        }
    }

    private void typeChar(int cp) {
        XKeycode key = null;
        boolean shift = false;
        if (cp >= 'a' && cp <= 'z') key = XKeycode.valueOf("KEY_" + (char) (cp - 32));
        else if (cp >= 'A' && cp <= 'Z') { key = XKeycode.valueOf("KEY_" + (char) cp); shift = true; }
        else if (cp == ' ') key = XKeycode.KEY_SPACE;
        else if (cp == '\n' || cp == '\r') { key = XKeycode.KEY_ENTER; cp = 0; }
        else if (cp == '\t') { key = XKeycode.KEY_TAB; cp = 0; }
        else {
            int u = UNSHIFTED.indexOf(cp), s = SHIFTED.indexOf(cp);
            if (u >= 0) key = vkMap[vkForUsChar(UNSHIFTED.charAt(u))];
            else if (s >= 0) { key = vkMap[vkForUsChar(UNSHIFTED.charAt(s))]; shift = true; }
        }
        if (key == null) key = XKeycode.KEY_A; // other scripts: the X server maps the character itself
        if (shift) xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
        xServer.injectKeyPress(key, cp);
        xServer.injectKeyRelease(key);
        if (shift) xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
    }

    /** US-layout VK code for an unshifted symbol/digit. */
    private static int vkForUsChar(char c) {
        if (c >= '0' && c <= '9') return c;
        switch (c) {
            case '`': return 0xC0; case '-': return 0xBD; case '=': return 0xBB;
            case '[': return 0xDB; case ']': return 0xDD; case '\\': return 0xDC;
            case ';': return 0xBA; case '\'': return 0xDE; case ',': return 0xBC;
            case '.': return 0xBE; case '/': return 0xBF;
            default: return 0;
        }
    }

    // ---- mouse ----

    private void mouseButton(int button, boolean down) {
        if (xServer == null) return;
        Pointer.Button b;
        switch (button) {
            case 1: b = Pointer.Button.BUTTON_LEFT; break;
            case 2: b = Pointer.Button.BUTTON_MIDDLE; break;
            case 3: b = Pointer.Button.BUTTON_RIGHT; break;
            case 4:
            case 5:
                // X1/X2 have no button in Winlator's X server: press them through Windows.
                if (winHandler != null)
                    winHandler.mouseEvent(down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP, 0, 0, button == 4 ? 1 : 2);
                return;
            default: return;
        }
        if (down) xServer.injectPointerButtonPress(b);
        else xServer.injectPointerButtonRelease(b);
    }

    private void scroll(int amount) {
        if (xServer == null) return;
        // 120 units per wheel notch (Windows WHEEL_DELTA); keep high-resolution remainders.
        scrollAccum += amount;
        while (Math.abs(scrollAccum) >= 120) {
            Pointer.Button b = scrollAccum > 0 ? Pointer.Button.BUTTON_SCROLL_UP : Pointer.Button.BUTTON_SCROLL_DOWN;
            xServer.injectPointerButtonPress(b);
            xServer.injectPointerButtonRelease(b);
            scrollAccum -= scrollAccum > 0 ? 120 : -120;
        }
    }

    // ---- gamepad ----

    private void multiController(ByteBuffer le, int p) {
        if (winHandler == null) return;
        int number = le.getShort(p + 2) & 0x0F;
        int mask = le.getShort(p + 4) & 0xFFFF;
        int buttons = le.getShort(p + 8) & 0xFFFF;
        int lt = le.get(p + 10) & 0xFF, rt = le.get(p + 11) & 0xFF;
        short lx = le.getShort(p + 12), ly = le.getShort(p + 14);
        short rx = le.getShort(p + 16), ry = le.getShort(p + 18);

        // Controllers that disappeared from the active mask release their slots.
        int gone = activeMask & ~mask;
        for (int i = 0; i < 16; i++) {
            if ((gone & (1 << i)) != 0 && pads.get(i) != null) {
                winHandler.releaseRemoteGamepad(i);
                pads.remove(i);
            }
        }
        activeMask = mask;
        if ((mask & (1 << number)) == 0) return;

        GamepadState s = pads.get(number);
        if (s == null) { s = new GamepadState(); pads.put(number, s); }
        s.buttons = 0;
        s.setPressed(ExternalController.IDX_BUTTON_A, (buttons & 0x1000) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_B, (buttons & 0x2000) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_X, (buttons & 0x4000) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_Y, (buttons & 0x8000) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_L1, (buttons & 0x0100) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_R1, (buttons & 0x0200) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_SELECT, (buttons & 0x0020) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_START, (buttons & 0x0010) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_L3, (buttons & 0x0040) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_R3, (buttons & 0x0080) != 0);
        s.setPressed(ExternalController.IDX_BUTTON_L2, lt > 30);
        s.setPressed(ExternalController.IDX_BUTTON_R2, rt > 30);
        s.dpad[0] = (buttons & 0x0001) != 0; // up
        s.dpad[1] = (buttons & 0x0008) != 0; // right
        s.dpad[2] = (buttons & 0x0002) != 0; // down
        s.dpad[3] = (buttons & 0x0004) != 0; // left
        s.triggerL = lt / 255f;
        s.triggerR = rt / 255f;
        // Moonlight sticks are XInput style (Y up positive); Winlator uses Android axes (Y down).
        s.thumbLX = lx / 32767f;
        s.thumbLY = -ly / 32767f;
        s.thumbRX = rx / 32767f;
        s.thumbRY = -ry / 32767f;
        winHandler.sendRemoteGamepadState(number, s);
    }
}
