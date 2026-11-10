#!/usr/bin/env python3
"""Exercise the geometry shared by the production EGL and DisplayX adapters."""
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
source = r'''
#include "native_backend_geometry.h"
#include <cassert>
#include <cmath>
int main() {
    xorg::NativeBackendGeometry g; NativeMagnifierPan pan;
    g.Update(1920, 1080, 1280, 1024, false, 1, 0, 0, pan);
    assert(g.sx == g.sy && g.left == 285 && g.top == 0);
    assert(std::abs(g.ox - 285) < .01 && g.right == 1635);
    g.Update(1920, 1080, 1280, 1024, true, 1, 0, 0, pan);
    assert(g.sx == 1.5f && std::abs(g.sy - 1080.f / 1024) < .001);
    assert(g.left == 0 && g.right == 1920 && g.bottom == 1080);
    g.Update(1920, 1080, 1280, 1024, false, 2, 1280, 1024, pan);
    assert(g.ox < 0 && g.oy < 0 && g.left == 285);
    assert(g.sx == g.sy && g.right == 1635);
    g.Update(1080, 1920, 1280, 720, false, 1, 100, 100, pan);
    assert(g.left == 0 && g.top > 600 && g.sx == g.sy && pan.x == 0 && pan.y == 0);
}
'''
with tempfile.TemporaryDirectory(prefix='ludashi-backend-geometry-') as folder:
    folder = pathlib.Path(folder)
    (folder / 'test.cpp').write_text(source)
    subprocess.run(['g++', '-std=c++20', '-Wall', '-Wextra', '-Werror', '-I' + str(root / 'include'),
                    str(folder / 'test.cpp'), '-o', str(folder / 'test')], check=True)
    subprocess.run([str(folder / 'test')], check=True)
print('PASS: EGL/DisplayX fit, stretch, letterbox clipping, portrait resize and cursor-following zoom')
