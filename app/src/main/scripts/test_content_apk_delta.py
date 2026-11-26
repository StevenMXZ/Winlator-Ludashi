import os
import random
import tempfile
import unittest
from pathlib import Path
import content_apk_delta as delta

class DeltaTest(unittest.TestCase):
    def test_shift_and_rebuild(self):
        with tempfile.TemporaryDirectory() as d:
            old, new, patch, out = [Path(d)/name for name in ("old", "new", "patch", "out")]
            data = random.Random(42).randbytes(4 * 1024 * 1024)
            old.write_bytes(data)
            new.write_bytes(b"inserted" + data[:1000000] + b"changed" + data[1000100:])
            delta.make(old, new, patch)
            delta.apply(old, patch, out)
            self.assertEqual(out.read_bytes(), new.read_bytes())
            self.assertLess(patch.stat().st_size, len(data)//3)
            old.write_bytes(b"wrong")
            with self.assertRaises(ValueError):
                delta.apply(old, patch, out)

if __name__ == "__main__":
    unittest.main()
