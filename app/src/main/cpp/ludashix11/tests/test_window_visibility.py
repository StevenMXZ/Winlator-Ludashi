#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile


def body(source, marker):
    start = source.index('{', source.index(marker))
    depth, end = 1, start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


source = (Path(__file__).resolve().parents[2] / 'winlator/renderer/renderer_jni.cpp').read_text()
harness = r'''
#include <cassert>
#include <functional>
#include <string>
struct Window { int attributes{}; bool mapped{}, enabled{true}, drawable{true}; std::string className; } window;
struct { Window* getWindow(int id) { return id == 7 ? &window : nullptr; } } windowManager;
struct { int windowAttributesIsEnabled{}; } cache;
struct Env {
    bool enabled{};
    bool CallBooleanMethod(int, int) { return enabled; }
    const char* GetStringUTFChars(const char* text, void*) { return text; }
    void ReleaseStringUTFChars(const char*, const char*) {}
};
struct { bool active{true}; bool isDisplayX() { return active; } } xserver;
struct {
    int maps{}, updates{};
    void queueWindowEvent(std::function<void()> f) { f(); }
    void mapWindow(Window*) { ++maps; }
    void requestWindowUpdate(Window*) { ++updates; }
} displayX;
struct {
    void queueEvent(std::function<void()> f) { f(); }
    void updateScene() {}
    void requestRenderer() {}
} renderer;
'''
harness += 'void map(Env* env, int id) ' + body(source, 'JNIEXPORT void JNICALL\nJava_com_winlator_cmod_widget_XServerView_nativeMapWindow')
harness += 'void setClass(Env* env, int id, const char* className) ' + body(source, 'Java_com_winlator_cmod_widget_XServerView_nativeSetWindowClassName')
harness += r'''
int main() {
    Env env;
    map(&env, 7);
    assert(window.mapped && !window.enabled && displayX.maps == 1);
    env.enabled = true; map(&env, 7);
    assert(window.enabled && displayX.maps == 2);
    env.enabled = false; setClass(&env, 7, "explorer.exe");
    assert(!window.enabled && window.className == "explorer.exe" && displayX.updates == 1);
    env.enabled = true; setClass(&env, 7, "game.exe");
    assert(window.enabled && displayX.updates == 2);
    map(&env, 99); setClass(&env, 99, "missing.exe");
    assert(displayX.maps == 2 && displayX.updates == 2);
    xserver.active = false; env.enabled = false; map(&env, 7);
    assert(!window.enabled && displayX.maps == 2);
}
'''
with tempfile.TemporaryDirectory() as directory:
    cpp = Path(directory) / 'visibility.cpp'
    binary = Path(directory) / 'visibility'
    cpp.write_text(harness)
    subprocess.run(['c++', '-std=c++17', str(cpp), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print('PASS: Java visibility reaches DisplayX on map and late WM_CLASS updates; EGL and invalid IDs remain safe')
