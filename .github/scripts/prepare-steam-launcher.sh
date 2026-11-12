#!/usr/bin/env bash
set -euo pipefail
expected=ecb3f71c1a8b7ebb4e205a34a118cb3acd8ab184f8ddf238d207018b14f1b1e2
actual="$(git ls-files app/src/main/cpp/ludashi-steam-launcher \
    app/src/main/assets/ludashisteam/bionic/steam.exe \
    app/src/main/assets/ludashisteam/bionic/steam32.exe | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -d ' ' -f1)"
if [[ "$actual" == "$expected" ]]; then
    echo 'Using the verified bundled Steam launchers; sources and binaries are unchanged.'
elif command -v x86_64-w64-mingw32-g++-posix >/dev/null 2>&1; then
    bash app/src/main/cpp/ludashi-steam-launcher/build.sh
else
    echo 'Steam launcher changed: rebuild it with MinGW and update its verified fingerprint.' >&2
    exit 1
fi
