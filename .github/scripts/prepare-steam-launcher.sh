#!/usr/bin/env bash
set -euo pipefail
expected=5b29598ba80f9982ce9cbfab421951f8a2b13b14cb20521019626b8d709662a7
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
