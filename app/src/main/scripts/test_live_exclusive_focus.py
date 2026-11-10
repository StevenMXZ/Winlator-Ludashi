#!/usr/bin/env python3
"""Exercise the production live toggle with asynchronous Wine/reconnect test doubles."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[4]
source = (root / "app/src/main/java/com/winlator/cmod/XServerDisplayActivity.java").read_text()
def extract_method(name):
    declaration = re.search(
        r"(?m)^    (?:public|private) void " + re.escape(name) + r"\(", source
    )
    if declaration is None:
        raise AssertionError(f"Missing live controller method: {name}")
    start = declaration.start()
    opening = source.index("{", declaration.end())
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]

method = "\n".join(extract_method(name) for name in (
    "changeExclusiveInput", "changeSidebarInputApis", "applySidebarJoystickSettings"
))

harness = r'''
package com.winlator.cmod.winhandler;
import java.io.File;
import java.util.*;
public class FocusHarness {
    static class Attr { boolean mapped = true; boolean isMapped() { return mapped; } }
    static class Window {
        final int id; long handle; Window parent; final Attr attributes = new Attr();
        Window(int id, long handle, Window parent) { this.id=id; this.handle=handle; this.parent=parent; }
        long getHandle() { return handle; } Window getParent() { return parent; }
    }
    static class Manager {
        Window focused; Map<Integer,Window> windows = new HashMap<>();
        Window getFocusedWindow() { return focused; } Window getWindow(int id) { return windows.get(id); }
    }
    static class Server { Manager windowManager = new Manager(); }
    static class Container {
        boolean exclusive = true; int inputType = 4; File getRootDir() { return new File("."); }
        void setExclusiveXInput(boolean value) { exclusive=value; }
        void setInputType(int value) { inputType=value; } void saveData() {}
    }
    static class Shortcut { void putExtra(String key,String value) {} void saveData() {} }
    public static class WinHandler {
        static final byte FLAG_INPUT_TYPE_XINPUT = 4, FLAG_INPUT_TYPE_DINPUT = 8;
        byte inputType = 4; boolean xinputDisabled;
        long activated; int activationCount; Runnable reconnect;
        byte getInputType() { return inputType; }
        void setInputType(byte value) { inputType=value; }
        void setXInputDisabled(boolean value) { xinputDisabled=value; }
        void holdGamepadsNeutral() {}
        void reconnectGamepads(Runnable callback) { reconnect=callback; }
        void bringToFront(String name,long handle) { activated=handle; activationCount++; }
        void finishReconnect() { Runnable done=reconnect; reconnect=null; done.run(); }
    }
    Server xServer=new Server(); WinHandler winHandler=new WinHandler(); Container container=new Container();
    Object xServerView;
    Shortcut shortcut; boolean exclusiveInputBusy, destroyed;
    boolean canChangeExclusiveInput() { return !exclusiveInputBusy; }
    boolean isExclusiveInputEnabled() { return container.exclusive; }
    boolean isSidebarXInputEnabled() { return (winHandler.getInputType() & WinHandler.FLAG_INPUT_TYPE_XINPUT) != 0; }
    boolean isSidebarDInputEnabled() { return (winHandler.getInputType() & WinHandler.FLAG_INPUT_TYPE_DINPUT) != 0; }
    boolean isFinishing() { return destroyed; } boolean isDestroyed() { return destroyed; }
''' + method + r'''
    static FocusHarness game() {
        LiveJoystickSettings.callbacks.clear();
        FocusHarness h = new FocusHarness();
        Window game = new Window(1,42,null), child=new Window(2,0,game);
        h.xServer.windowManager.windows.put(1,game);
        h.xServer.windowManager.focused=child;
        return h;
    }
    static void check(boolean yes) { if (!yes) throw new AssertionError(); }
    static void applyResult(boolean ok) { LiveJoystickSettings.callbacks.remove(0).done(ok); }
    public static void main(String[] args) {
        FocusHarness h=game();
        h.changeExclusiveInput(false,()->{});
        // A helper takes focus while the registry import is running.
        h.xServer.windowManager.focused = new Window(3,99,null);
        applyResult(true);
        check(h.winHandler.activationCount==0);
        h.winHandler.finishReconnect();
        check(h.winHandler.activated==42 && !h.container.exclusive && !h.exclusiveInputBusy);
        check(h.winHandler.inputType==12 && h.container.inputType==12);

        h=game(); h.changeExclusiveInput(false,()->{}); applyResult(false);
        check(LiveJoystickSettings.callbacks.size()==1); // Restore previous registry policy first.
        applyResult(true); h.winHandler.finishReconnect();
        check(h.winHandler.activated==42 && h.container.exclusive);

        for (int scenario=0; scenario<4; scenario++) {
            h=game(); h.changeExclusiveInput(false,()->{}); applyResult(true);
            Window old=h.xServer.windowManager.windows.get(1);
            if (scenario==0) h.xServer.windowManager.windows.put(1,new Window(1,43,null));
            if (scenario==1) old.attributes.mapped=false;
            if (scenario==2) old.handle=43;
            if (scenario==3) h.destroyed=true;
            h.winHandler.finishReconnect();
            check(h.winHandler.activationCount==0);
        }
        h=game(); h.xServer.windowManager.focused=new Window(0,0,null);
        h.changeExclusiveInput(false,()->{}); applyResult(true); h.winHandler.finishReconnect();
        check(h.winHandler.activationCount==0);
        h=game(); h.changeSidebarInputApis(false, true, ()->{});
        // Exclusive mode allows switching from XInput to DInput without losing focus.
        applyResult(true); h.winHandler.finishReconnect();
        check(h.winHandler.inputType==8 && h.container.inputType==8 && h.winHandler.activated==42);
        h=game(); h.changeSidebarInputApis(false, true, ()->{});
        applyResult(false); applyResult(true); h.winHandler.finishReconnect();
        check(h.winHandler.inputType==4 && h.container.inputType==4 && h.container.exclusive);
        System.out.println("Live controller input: XInput/DInput, focus, rollback and closed/reused windows passed");
    }
}
'''
helper = r'''
package com.winlator.cmod.winhandler;
import java.io.File;
import java.util.*;
class LiveJoystickSettings {
    interface Callback { void done(boolean ok); }
    static List<Callback> callbacks = new ArrayList<>();
    static void apply(FocusHarness.WinHandler handler,File drive,boolean dinput,boolean exclusive,Callback done) {
        callbacks.add(done);
    }
}
'''
toast = '''package android.widget;
public class Toast {
 public static final int LENGTH_LONG=1;
 public static Toast makeText(Object context,String message,int length) { return new Toast(); }
 public void show() {}
}
'''
native_view = '''package com.winlator.cmod.widget;
public class NativeX11View {
 public long getFocusedWindowHandle() { return 42; }
 public boolean hasWindowHandle(long h) { return h==42; }
}
'''
with tempfile.TemporaryDirectory() as work:
    files = []
    for name, content in {"FocusHarness.java": harness, "LiveJoystickSettings.java": helper, "Toast.java": toast,
                          "NativeX11View.java": native_view}.items():
        p = Path(work) / name
        p.write_text(content)
        files.append(str(p))
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", work, *files], check=True)
    subprocess.run(["java", "-cp", work, "com.winlator.cmod.winhandler.FocusHarness"], check=True)
