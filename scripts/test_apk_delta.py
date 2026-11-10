#!/usr/bin/env python3
"""Exercise byte-exact reconstruction, entry moves and damaged downloads."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("apk_delta", Path(__file__).with_name("apk_delta.py"))
delta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delta)

class DeltaTests(unittest.TestCase):
    def test_reordered_entries_and_changed_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            old, new, patch, rebuilt = [root / name for name in ("old.apk", "new.apk", "update.bldp", "rebuilt.apk")]
            for path, entries in [(old, [("asset", b"same" * 20000), ("classes.dex", b"old")]),
                                  (new, [("classes.dex", b"new dex"), ("asset", b"same" * 20000)])]:
                with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
                    for name, data in entries: archive.writestr(name, data)
                    archive.comment = b"different signed APK metadata:" + path.name.encode()
            delta.make(old, new, patch)
            self.assertTrue(delta.apply(old, patch, rebuilt))
            self.assertEqual(new.read_bytes(), rebuilt.read_bytes())
            patch.write_bytes(patch.read_bytes()[:-5])
            with self.assertRaises((ValueError, EOFError, Exception)):
                delta.apply(old, patch, rebuilt)

if __name__ == "__main__": unittest.main()
