#!/usr/bin/env python3
"""Check CriticalNative's primitive ABI and explicit Android 9–11 registration."""
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[6]
cpp_root = root / 'app/src/main/cpp/winlator'
native = '\n'.join(p.read_text() for pattern in ('*.c', '*.cpp') for p in cpp_root.rglob(pattern))
types = {'void': ('V', 'void'), 'byte': ('B', 'jbyte'), 'short': ('S', 'jshort'),
         'int': ('I', 'jint'), 'long': ('J', 'jlong'), 'boolean': ('Z', 'jboolean'),
         'float': ('F', 'jfloat'), 'double': ('D', 'jdouble'), 'char': ('C', 'jchar')}
count = 0
for source in (root / 'app/src/main/java/com/winlator/cmod').rglob('*.java'):
    code = source.read_text()
    package = re.search(r'package\s+([\w.]+);', code).group(1)
    for match in re.finditer(r'@CriticalNative\s+((?:(?:public|private|protected|static|final|native)\s+)+)(\w+)\s+(\w+)\(([^)]*)\)', code):
        modifiers, returns, name, arguments = match.groups()
        assert 'static' in modifiers and 'native' in modifiers, (source, name)
        args = [a.strip().split()[0] for a in arguments.split(',')] if arguments.strip() else []
        assert returns in types and all(a in types for a in args), (source, name, args)
        signature = '(' + ''.join(types[a][0] for a in args) + ')' + types[returns][0]
        symbol = 'Java_' + package.replace('.', '_') + '_' + source.stem + '_' + name
        registration = r'\{"' + name + r'",\s*"' + re.escape(signature) + r'",[^\n]*\b' + symbol + r'\b'
        assert re.search(registration, native), ('Missing explicit registration', source, name, signature)
        declaration = re.search(r'\b' + symbol + r'\(([^)]*)\)\s*\{', native)
        assert declaration, ('Missing native entry', symbol)
        actual = [a.strip().split()[0] for a in declaration.group(1).split(',')] if declaration.group(1).strip() else []
        actual = ['jint' if a == 'int' else a for a in actual]
        assert actual == [types[a][1] for a in args], ('JNI ABI mismatch', symbol, actual, args)
        count += 1
assert count > 0
print(f'PASS: {count} CriticalNative methods have primitive/static ABI and explicit registration')
