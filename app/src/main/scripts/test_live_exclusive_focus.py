#!/usr/bin/env python3
"""Exercise the production live toggle with asynchronous Wine/reconnect test doubles."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[4]
source = (root / "app/src/main/java/com/winlator/cmod/XServerDisplayActivity.java").read_text()
start = source.index("    public void changeExclusiveInput(")
opening = source.index("{", start)
depth = 1
end = opening + 1
while depth:
    depth += (source[end] == "{") - (source[end] == "}")
    end += 1
method = source[start:end]

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
        boolean exclusive = true; File getRootDir() { return new File("."); }
        void setExclusiveXInput(boolean value) { exclusive=value; } void saveData() {}
    }
    static class Shortcut { void putExtra(String key,String value) {} void saveData() {} }
    public static class WinHandler {
        static final int FLAG_INPUT_TYPE_DINPUT = 2;
        long activated; int activationCount; Runnable reconnect;
        int getInputType() { return 3; } void holdGamepadsNeutral() {}
        void reconnectGamepads(Runnable callback) { reconnect=callback; }
        void bringToFront(String name,long handle) { activated=handle; activationCount++; }
        void finishReconnect() { Runnable done=reconnect; reconnect=null; done.run(); }
    }
    Server xServer=new Server(); WinHandler winHandler=new WinHandler(); Container container=new Container();
    Shortcut shortcut; boolean exclusiveInputBusy, destroyed;
    boolean canChangeExclusiveInput() { return !exclusiveInputBusy; }
    boolean isExclusiveInputEnabled() { return container.exclusive; }
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
        System.out.println("Live Exclusive Input: success, rollback, original HWND and closed/reused windows passed");
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
with tempfile.TemporaryDirectory() as work:
    files = []
    for name, content in {"FocusHarness.java": harness, "LiveJoystickSettings.java": helper, "Toast.java": toast}.items():
        p = Path(work) / name
        p.write_text(content)
        files.append(str(p))
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", work, *files], check=True)
    subprocess.run(["java", "-cp", work, "com.winlator.cmod.winhandler.FocusHarness"], check=True)
