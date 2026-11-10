#!/usr/bin/env python3
"""Publish the signed APK and verified BLDP patches to the public distribution repository."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

REPO = "Other-backup/winlator-imagefs-v2"

def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)

def digest(path):
    with open(path, "rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()

def main():
    apk = Path(sys.argv[1]).resolve()
    code, version = int(sys.argv[2]), sys.argv[3]
    tag = f"ludashi-upstream-{code}"
    with tempfile.TemporaryDirectory() as work:
        work = Path(work)
        assets = []
        patches = {}
        releases = json.loads(gh("release", "list", "--repo", REPO, "--limit", "100", "--json", "tagName"))
        old_tags = [r["tagName"] for r in releases if r["tagName"].startswith("ludashi-upstream-") and r["tagName"] != tag][:8]
        for old_tag in old_tags:
            dest = work / old_tag
            dest.mkdir()
            try:
                gh("release", "download", old_tag, "--repo", REPO, "--pattern", "*.apk", "--dir", str(dest))
                old = next(dest.glob("*.apk"))
                patch = work / f"update-{code}-from-{digest(old)[:16]}.bldp"
                subprocess.run([sys.executable, "app/src/main/scripts/apk_delta.py", "make", str(old), str(apk), str(patch)], check=True)
                restored = dest / "restored.apk"
                subprocess.run([sys.executable, "app/src/main/scripts/apk_delta.py", "apply", str(old), str(patch), str(restored)], check=True)
                if digest(restored) != digest(apk) or patch.stat().st_size >= apk.stat().st_size:
                    continue
                assets.append(patch)
                patches[digest(old)] = {"url": f"https://github.com/{REPO}/releases/download/{tag}/{patch.name}",
                    "sha256": digest(patch), "size": patch.stat().st_size}
            except (subprocess.CalledProcessError, StopIteration) as error:
                print(f"No small update from {old_tag}: {error}", file=sys.stderr)
        manifest = {"schema": 1, "packageName": "com.winlator.cmod", "versionCode": code, "versionName": version,
            "apkUrl": f"https://github.com/{REPO}/releases/download/{tag}/{apk.name}",
            "size": apk.stat().st_size, "sha256": digest(apk), "patches": patches,
            "notes": "Winlator Ludashi upstream update"}
        mfile = work / "update.json"
        mfile.write_text(json.dumps(manifest, indent=2) + "\n")
        gh("release", "create", tag, "--repo", REPO, "--title", f"Ludashi {version}", "--notes", "Upstream app update", "--draft",
           str(apk), str(mfile), *map(str, assets))
        gh("release", "edit", tag, "--repo", REPO, "--draft=false", "--latest=false")
        # Publish the manifest only after all of its assets are available. The ordinary source
        # branch is left alone; only this distribution branch is advanced.
        repo_dir = work / "distribution"
        repo_dir.mkdir()
        subprocess.run(["git", "-C", str(repo_dir), "init"], check=True)
        subprocess.run(["git", "-C", str(repo_dir), "remote", "add", "origin", f"https://github.com/{REPO}.git"], check=True)
        fetched = subprocess.run(["git", "-C", str(repo_dir), "fetch", "--depth=1", "origin", "upstream-updates"])
        if fetched.returncode == 0:
            subprocess.run(["git", "-C", str(repo_dir), "checkout", "-B", "upstream-updates", "FETCH_HEAD"], check=True)
        else:
            # Prove the repository is reachable before treating a missing branch as first publication.
            subprocess.run(["git", "-C", str(repo_dir), "ls-remote", "origin"], check=True)
            subprocess.run(["git", "-C", str(repo_dir), "checkout", "--orphan", "upstream-updates"], check=True)
        (repo_dir / "update.json").write_text(mfile.read_text())
        for key, value in [("user.name", "StevenMXZ"), ("user.email", "alab01845@gmail.com")]:
            subprocess.run(["git", "-C", str(repo_dir), "config", key, value], check=True)
        subprocess.run(["git", "-C", str(repo_dir), "add", "update.json"], check=True)
        subprocess.run(["git", "-C", str(repo_dir), "commit", "-m", f"Publish upstream update {version}"], check=True)
        subprocess.run(["git", "-C", str(repo_dir), "push", "origin", "HEAD:upstream-updates"], check=True)

if __name__ == "__main__":
    main()
