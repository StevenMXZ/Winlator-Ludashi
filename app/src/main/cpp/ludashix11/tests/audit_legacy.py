#!/usr/bin/env python3
"""Compare core/base with a supplied legacy snapshot; this is not a provenance or completion proof."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("reference", type=Path, help="Legacy source directory containing core/ and base/")
parser.add_argument("--current", type=Path, default=Path(__file__).resolve().parents[1])
args = parser.parse_args()
for root in (args.reference, args.current):
    for name in ("core", "base"):
        if not (root / name).is_dir(): parser.error(f"Missing {root / name}")

def sources(root):
    return {str(path.relative_to(root)): path for name in ("core", "base")
            for path in (root / name).rglob("*") if path.is_file()}

reference, current = sources(args.reference), sources(args.current)
shared = reference.keys() & current.keys()
identical = sorted(name for name in shared if reference[name].read_bytes() == current[name].read_bytes())
result = {
    "scope": ["core", "base"],
    "reference_directory": str(args.reference.resolve()),
    "current_directory": str(args.current.resolve()),
    "limitations": "Text comparison only. Modified files may still contain inherited code. "
                   "New paths may contain moved code. Counts are not a completion percentage or protocol coverage.",
    "identical": identical,
    "modified": sorted(shared - set(identical)),
    "removed_paths": sorted(reference.keys() - current.keys()),
    "new_paths": sorted(current.keys() - reference.keys()),
}
result["counts"] = {name: len(result[name]) for name in ("identical", "modified", "removed_paths", "new_paths")}
result["reference_file_count"] = len(reference)
result["reference_path_percentages"] = {
    name: round(100 * result["counts"][name] / len(reference), 2) if reference else 0
    for name in ("identical", "modified", "removed_paths")
}
print(json.dumps(result, indent=2))
