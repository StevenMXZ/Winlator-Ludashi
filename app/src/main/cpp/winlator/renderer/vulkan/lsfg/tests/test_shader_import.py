"""Host tests for PE extraction and caching; fixtures contain no Lossless Scaling shaders.

Run: python3 app/src/main/cpp/winlator/renderer/vulkan/lsfg/tests/test_shader_import.py
"""
import ctypes
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

IDS = [255, 256, *range(280, 303)]
MAGIC = 0x07230203
# Synthetic SPIR-V header/decorations used to test binding remapping, not GPU execution.
WORDS = [MAGIC, 0x00010000, 0, 16, 0,
         4 << 16 | 71, 7, 34, 0, 4 << 16 | 71, 7, 33, 8,
         4 << 16 | 71, 3, 34, 0, 4 << 16 | 71, 3, 33, 4]
SHADER = struct.pack('<' + 'I' * len(WORDS), *WORDS)


def pe(resources):
    """Minimal PE32+ image with an RCDATA tree: type -> id -> language -> blob."""
    resources = sorted(resources.items())
    data = bytearray(24 + 16 + 8 * len(resources))
    struct.pack_into('<HHII', data, 12, 0, 1, 10, 0x80000000 | 24)
    struct.pack_into('<HH', data, 24 + 12, 0, len(resources))
    leaves = []
    for index, (resource_id, blob) in enumerate(resources):
        language = len(data)
        data.extend(bytes(24))
        leaf = len(data)
        data.extend(bytes(16))
        struct.pack_into('<II', data, 40 + index * 8, resource_id, 0x80000000 | language)
        struct.pack_into('<HHII', data, language + 12, 0, 1, 1033, leaf)
        leaves.append((leaf, blob))
    for leaf, blob in leaves:
        data.extend(bytes((-len(data)) % 4))
        struct.pack_into('<IIII', data, leaf, 0x1000 + len(data), len(blob), 0, 0)
        data.extend(blob)
    header = bytearray(512)
    struct.pack_into('<H', header, 0, 0x5a4d)
    struct.pack_into('<I', header, 0x3c, 0x80)
    struct.pack_into('<I', header, 0x80, 0x4550)
    struct.pack_into('<HH', header, 0x84, 0x8664, 1)
    struct.pack_into('<H', header, 0x84 + 16, 240)
    optional = 0x98
    struct.pack_into('<H', header, optional, 0x20b)
    struct.pack_into('<I', header, optional + 108, 16)
    struct.pack_into('<II', header, optional + 112 + 16, 0x1000, len(data))
    section = optional + 240
    header[section:section + 8] = b'.rsrc\0\0\0'
    struct.pack_into('<IIII', header, section + 8, len(data), 0x1000, len(data), 512)
    return bytes(header + data)


def variants(*offsets, shader=SHADER):
    return {shader_id + offset: shader for offset in offsets for shader_id in IDS}


class Module(ctypes.Structure):
    _fields_ = [('id', ctypes.c_uint32), ('words', ctypes.POINTER(ctypes.c_uint32)),
                ('word_count', ctypes.c_uint32)]


class ModuleSet(ctypes.Structure):
    _fields_ = [('modules', Module * 25), ('count', ctypes.c_uint32), ('variant', ctypes.c_int)]


class ShaderImportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        include = cls.directory / 'android'
        include.mkdir()
        (include / 'log.h').write_text(
            '#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_ERROR 6\n'
            'static inline int __android_log_print(int p, const char* t, const char* f, ...) '
            '{ (void)p; (void)t; (void)f; return 0; }\n')
        source = Path(__file__).resolve().parents[1] / 'lsfg_dll.c'
        library = cls.directory / 'libimport.so'
        subprocess.run([os.environ.get('CC', 'cc'), '-std=gnu11', '-shared', '-fPIC',
                        '-Wall', '-Wextra', '-Werror', '-I', str(cls.directory), str(source),
                        '-o', str(library)], check=True)
        cls.lib = ctypes.CDLL(str(library))
        cls.lib.lsfg_build_cache.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_bool]
        cls.lib.lsfg_build_cache.restype = ctypes.c_int
        cls.lib.lsfg_load_modules.argtypes = [ctypes.c_char_p, ctypes.POINTER(ModuleSet)]
        cls.lib.lsfg_load_modules.restype = ctypes.c_int
        cls.lib.lsfg_release_modules.argtypes = [ctypes.POINTER(ModuleSet)]

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def build(self, resources, prefer_fp16=True):
        dll = self.directory / 'fixture.dll'
        cache = self.directory / 'fixture.cache'
        dll.write_bytes(pe(resources))
        return self.lib.lsfg_build_cache(os.fsencode(dll), os.fsencode(cache), prefer_fp16), cache

    def test_native_variants_and_binding_remapping(self):
        for offsets, preferred, expected in [((49,), True, 1), ((49,), False, 1),
                                              ((98,), True, 2), ((49, 98), True, 1),
                                              ((49, 98), False, 2)]:
            with self.subTest(offsets=offsets, preferred=preferred):
                status, cache = self.build(variants(*offsets), preferred)
                self.assertEqual(status, 0)
                modules = ModuleSet()
                self.assertEqual(self.lib.lsfg_load_modules(os.fsencode(cache), ctypes.byref(modules)), 0)
                try:
                    self.assertEqual(modules.variant, expected)
                    self.assertEqual(modules.count, 25)
                    self.assertEqual([m.id for m in modules.modules], IDS)
                    self.assertEqual(modules.modules[0].words[12], 1)
                    self.assertEqual(modules.modules[0].words[20], 0)
                finally:
                    self.lib.lsfg_release_modules(ctypes.byref(modules))

    def test_dxbc_only_and_incomplete_native_sets_are_rejected(self):
        for resources in [variants(0, shader=b'DXBC' + bytes(28)),
                          {key: value for key, value in variants(49).items() if key != 304}]:
            with self.subTest(count=len(resources)):
                status, _ = self.build(resources)
                self.assertEqual(status, 4)

    def test_malformed_shader_does_not_replace_an_existing_cache(self):
        status, cache = self.build(variants(49))
        self.assertEqual(status, 0)
        before = cache.read_bytes()
        malformed = bytearray(SHADER)
        struct.pack_into('<I', malformed, 20, 0)
        status, _ = self.build(variants(49, shader=bytes(malformed)))
        self.assertEqual(status, 5)
        self.assertEqual(cache.read_bytes(), before)

    def test_legacy_translated_and_truncated_caches_are_rejected(self):
        status, cache = self.build(variants(98))
        self.assertEqual(status, 0)
        original = cache.read_bytes()
        legacy = bytearray(original)
        struct.pack_into('<I', legacy, 4, 1)
        translated = bytearray(original)
        struct.pack_into('<I', translated, 12, 3)
        for invalid in [bytes(legacy), bytes(translated), original[:10], original[:-4]]:
            cache.write_bytes(invalid)
            modules = ModuleSet()
            self.assertNotEqual(self.lib.lsfg_load_modules(os.fsencode(cache), ctypes.byref(modules)), 0)
            self.assertEqual(modules.count, 0)

    def test_truncated_pe_inputs_are_rejected(self):
        complete = pe(variants(49))
        dll = self.directory / 'truncated.dll'
        for length in [0, 30, 100, 300, 512, len(complete) - 1]:
            with self.subTest(length=length):
                dll.write_bytes(complete[:length])
                status = self.lib.lsfg_build_cache(os.fsencode(dll), os.fsencode(self.directory / 'bad.cache'), True)
                self.assertNotEqual(status, 0)


if __name__ == '__main__':
    unittest.main()
