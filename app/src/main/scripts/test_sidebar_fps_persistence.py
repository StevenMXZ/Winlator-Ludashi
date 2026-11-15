#!/usr/bin/env python3
"""Run production sidebar persistence methods against a host metadata/widget fixture.

This checks save ordering and shortcut inheritance, not Android rendering or timing.
"""
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1] / 'java/com/winlator/cmod'
activity = (root / 'XServerDisplayActivity.java').read_text()
control = (root / 'ui/FpsLimiterControl.java').read_text()

def method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

fixture = r'''
import java.util.*;
public class SidebarFpsPersistenceTest {
    static class Container {
        Map<String,String> values = new HashMap<>();
        String getExtra(String key, String fallback) { return values.getOrDefault(key, fallback); }
        void putExtra(String key, String value) { if(value == null) values.remove(key); else values.put(key,value); }
        void saveData() {}
    }
    static class Shortcut extends Container {
        Map<String,String> disk = new HashMap<>();
        void saveData() { disk = new HashMap<>(values); }
    }
    static class Switch { boolean isChecked() { return false; } }
    static class Spinner { int getSelectedItemPosition() { return 0; } }
    static class SeekBar {
        int progress;
        int getSelectedItemPosition() { return progress; }
        int getProgress() { return progress; }
        void setProgress(int value) { progress = value; }
        float getValue() { return 50f; }
    }
    static class Text { String value = ""; void setText(String s) { value = s; } String getText() { return value; } }
    static class R { static class id {
        static final int SWEnableFSR=1, SPUpscalerMode=2, SPPostFXMode=3, SPNativeFPS=4, SBSharpness=5;
    } }
    static class XServerRendererView { int limit; void setFpsLimit(int value) { limit=value; } }
    static class XServerDisplayActivity {
        boolean graphicsControlsReady=true;
        Container container=new Container(); Shortcut shortcut=new Shortcut();
        XServerRendererView renderer=new XServerRendererView();
        Map<Integer,Object> views=new HashMap<>();
        XServerDisplayActivity() {
            views.put(1,new Switch()); views.put(2,new Spinner()); views.put(3,new Spinner()); views.put(5,new SeekBar());
        }
        @SuppressWarnings("unchecked") <T> T findViewById(int id) { return (T)views.get(id); }
        Container getContainer() { return container; }
        XServerRendererView getXServerView() { return renderer; }
        ACTIVITY_METHODS
    }
    static class Control {
        static final String EXTRA_ENABLED="nativeFpsLimiterEnabled", EXTRA_LIMIT="nativeFpsLimit";
        static final int SLIDER_MAX_FPS=120, STEP_FPS=5, CUSTOM_POSITION=24;
        boolean initializing;
        SeekBar slider=new SeekBar(); Text customValue=new Text();
        XServerDisplayActivity activity;
        Control(XServerDisplayActivity a) { activity=a; }
        XServerDisplayActivity findActivity() { return activity; }
        void updateLimitUi() {}
        CONTROL_METHODS
    }
    static void equal(Object expected, Object actual) {
        if(!Objects.equals(expected,actual)) throw new AssertionError(expected+" != "+actual);
    }
    public static void main(String[] args) {
        XServerDisplayActivity a=new XServerDisplayActivity();
        a.container.putExtra("nativeFpsLimit","60");
        Control fps=new Control(a);
        // Save a shortcut-only choice, then save unrelated graphics/exit state.
        fps.slider.setProgress(9); fps.saveAndApply();
        a.saveSidebarGraphicsState();
        equal("45",a.shortcut.disk.get("nativeFpsLimit"));
        equal("60",a.container.getExtra("nativeFpsLimit",""));
        equal(false,a.shortcut.disk.containsKey("graphicsFpsPreset"));
        Control reopened=new Control(a);
        reopened.loadState(a.container,a.shortcut);
        equal(45,reopened.getChosenLimit());
        // Custom FPS, including disabling an inherited limit, survives autosave.
        fps.slider.setProgress(24); fps.customValue.setText("73"); fps.saveAndApply();
        a.saveSidebarGraphicsState(); equal("73",a.shortcut.disk.get("nativeFpsLimit"));
        fps.slider.setProgress(0); fps.saveAndApply();
        a.saveSidebarGraphicsState(); equal("0",a.shortcut.disk.get("nativeFpsLimit"));
        // Untouched shortcuts must continue inheriting the container limit.
        a.shortcut=new Shortcut(); a.saveSidebarGraphicsState();
        Control inherited=new Control(a); inherited.loadState(a.container,a.shortcut);
        equal(60,inherited.getChosenLimit());
        // A real legacy spinner can still persist its preset.
        a.views.put(4,new Spinner()); a.saveSidebarGraphicsState();
        equal("0",a.shortcut.disk.get("graphicsFpsPreset"));
        // Container sessions still use the same persistence path.
        a.shortcut=null; fps.slider.setProgress(6); fps.saveAndApply();
        equal("30",a.container.getExtra("nativeFpsLimit",""));
        System.out.println("PASS: sidebar FPS shortcut saves, exit saves, inheritance, custom/off and container sessions");
    }
}
'''
fixture = fixture.replace('ACTIVITY_METHODS', '\n'.join(method(activity, s) for s in [
    'private void saveSidebarGraphicsState()', 'public Shortcut getShortcut()']))
fixture = fixture.replace('CONTROL_METHODS', '\n'.join(method(control, s) for s in [
    'private void loadState(', 'private void saveAndApply()', 'private int getChosenLimit()', 'private int parsePositiveOrZero(']))
with tempfile.TemporaryDirectory(prefix='ludashi-fps-') as folder:
    path = pathlib.Path(folder)
    java = path / 'SidebarFpsPersistenceTest.java'
    java.write_text(fixture)
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', str(java)], check=True)
    subprocess.run(['java', '-cp', folder, 'SidebarFpsPersistenceTest'], check=True)
