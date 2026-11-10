#!/usr/bin/env python3
"""Host tests for both presentation-only magnifier implementations, no Android SDK needed."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / "app/src/main/java/com/winlator/cmod/renderer/MagnifierPan.java"
CPP = ROOT / "app/src/main/cpp/ludashix11/include/native_magnifier.h"
NATIVE_VIEW = ROOT / "app/src/main/java/com/winlator/cmod/widget/NativeX11View.java"
NATIVE_SCREEN = ROOT / "app/src/main/cpp/ludashix11/src/native_screen.cpp"
VULKAN_VIEW = ROOT / "app/src/main/java/com/winlator/cmod/widget/VulkanXServerView.java"

# Zoom must transform presentation rather than resize the SurfaceView or X11.
native = NATIVE_VIEW.read_text()
screen = NATIVE_SCREEN.read_text()
vulkan = VULKAN_VIEW.read_text()
assert "nativeMagnifierZoom(handle, zoom)" in native
assert "setScaleX(" not in native and "setScaleY(" not in native
assert "void NativeScreen::SetMagnifierZoom(" in screen
assert "nativeSetTransformAndScissor" in vulkan
assert "magnifierPan.update(" in vulkan

java_harness = r"""
import com.winlator.cmod.renderer.MagnifierPan;
public class MagnifierPanTest {
    static void close(float a, float b) {
        if (Math.abs(a-b) > .02f) throw new AssertionError(a + " != " + b);
    }
    public static void main(String[] args) {
        MagnifierPan p = new MagnifierPan();
        p.update(640,360,1280,720,1f);
        close(p.panX(),0);
        p.update(640,360,1280,720,1.25f);
        close(p.panX(),160);
        close(p.panY(),90);
        p.update(650,365,1280,720,1.25f);
        close(p.panX(),160); // cursor deadzone: no jitter
        p.update(1280,720,1280,720,1.25f);
        close(p.panX(),320);
        close(p.panY(),180);
        p.update(0,0,1280,720,1.25f);
        close(p.panX(),0);
        close(p.panY(),0);
        p.update(640,360,1280,720,1.5f);
        close(p.panX(),320);
        close(p.panY(),180);
        p.update(640,360,1280,720,1f);
        close(p.panX(),0);
        close(p.panY(),0);
        System.out.println("Java Vulkan magnifier: 100/125/150%, follow, deadzone and reset OK");
    }
}
"""

cpp_harness = r"""
#include <cmath>
#include <iostream>
#include "native_magnifier.h"
static void close(float a, float b) {
    if (std::abs(a-b) > .02f) std::abort();
}
int main() {
    NativeMagnifierPan p;
    p.Update(640,360,1280,720,1.f);
    close(p.x,0);
    p.Update(640,360,1280,720,1.25f);
    close(p.x,160); close(p.y,90);
    p.Update(650,365,1280,720,1.25f);
    close(p.x,160);
    p.Update(1280,720,1280,720,1.25f);
    close(p.x,320); close(p.y,180);
    p.Update(0,0,1280,720,1.25f);
    close(p.x,0); close(p.y,0);
    p.Update(640,360,1280,720,1.5f);
    close(p.x,320); close(p.y,180);
    p.Update(640,360,1280,720,1.f);
    close(p.x,0); close(p.y,0);
    std::cout << "X++ magnifier: 100/125/150%, follow, deadzone and reset OK\\n";
}
"""

with tempfile.TemporaryDirectory() as tmp:
    d = Path(tmp)
    (d / "MagnifierPanTest.java").write_text(java_harness)
    subprocess.run(["javac", "-d", str(d), str(JAVA), str(d / "MagnifierPanTest.java")], check=True)
    subprocess.run(["java", "-cp", str(d), "MagnifierPanTest"], check=True)
    (d / "magnifier_test.cpp").write_text(cpp_harness)
    subprocess.run([
        "g++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
        "-I", str(CPP.parent), str(d / "magnifier_test.cpp"),
        "-o", str(d / "magnifier_test")
    ], check=True)
    subprocess.run([str(d / "magnifier_test")], check=True)
