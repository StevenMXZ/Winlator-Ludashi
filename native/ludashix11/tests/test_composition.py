#!/usr/bin/env python3
"""Run the real command recorder with a host Vulkan command capture, in both builds.

This exercises the production control flow and emitted pipeline/pass/alpha state.
It does not emulate shader execution or an Android Vulkan driver.
"""
import os
import pathlib
import re
import subprocess
import tempfile

root=pathlib.Path(__file__).resolve().parents[3]
renderer=root/'app/src/main/cpp/winlator/renderer/vulkan'
tests=pathlib.Path(__file__).resolve().parent
cmake=(root/'app/src/main/cpp/CMakeLists.txt').read_text()
target=re.search(r'add_library\(vulkan_renderer SHARED(.*?)\n\)',cmake,re.S)
assert target, 'Cannot find the renderer target'
recorders=[]
for entry in target.group(1).split():
    candidate=root/'app/src/main/cpp'/entry
    if entry.endswith('.cpp') and candidate.is_file():
        content=candidate.read_text()
        if 'void VulkanRendererContext::recordCmdBuf(' in content:
            recorders.append((candidate,content))
assert len(recorders)==1, f'Expected one linked recorder, found {[p for p,_ in recorders]}'
active,source=recorders[0]
print('Testing linked command recorder:',active.name,flush=True)
start=source.index('void VulkanRendererContext::recordCmdBuf(')
end=source.rindex('\n}')+2
header=(renderer/'VulkanRendererContext.h').read_text()
constants=header[header.index('struct WindowPushConstants '):header.index('\nclass VulkanRendererContext')]
with tempfile.TemporaryDirectory(prefix='native-composition-') as directory:
    directory=pathlib.Path(directory)
    unit=directory/'test.cpp'
    unit.write_text('#include "recording.h"\n'+constants+'\n'+source[start:end]+'\n'+(tests/'test_composition.cpp').read_text())
    for native in (False,True):
        binary=directory/('native' if native else 'java')
        flags=['-DLUDASHI_NATIVE_X11=1'] if native else []
        subprocess.run([os.environ.get('CXX','g++'),'-std=c++20','-O1','-g',
                        '-fsanitize=address,undefined','-fno-omit-frame-pointer',*flags,
                        '-I'+str(tests/'renderer_stubs'),'-I'+str(renderer),str(unit),'-o',str(binary)],check=True)
        subprocess.run([str(binary)],check=True)
