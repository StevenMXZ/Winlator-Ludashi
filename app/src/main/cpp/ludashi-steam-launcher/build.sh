#!/usr/bin/env bash
# Cross-compile steam.exe (the Steam Launcher in-Wine host) for Wine (x86_64 /
# 64-bit PE) and stage it into the APK assets. The Android Gradle build does NOT
# compile this — it only packages the prebuilt binary — so run this after editing
# any ludashi-steam-launcher source, then rebuild the APK.
#
# Built 64-bit on purpose: it hosts Valve's real steamclient64.dll so
# IClientAppManager::LaunchApp drives the game through steamclient's own
# app-launch path. Named "steam.exe" because steamclient's CGameLauncher path
# requires its host process to look like real Steam.
#
# Usage:   ./build.sh
# Output:  ../../assets/ludashisteam/bionic/steam.exe
#
# Requires the POSIX-threads mingw-w64 cross compiler (clean_shutdown.cpp uses
# std::thread, which the default win32-threads variant does not provide).
set -euo pipefail
cd "$(dirname "$0")"

CXX="${CXX:-x86_64-w64-mingw32-g++-posix}"
STRIP="${STRIP:-x86_64-w64-mingw32-strip}"
OUT_FILE="../../assets/ludashisteam/bionic/steam.exe"

# -Wl,--subsystem,windows -Wl,--no-insert-timestamp: no console, so Wine doesn't map a transient console
# X11 window at startup (which raced the X server and cut the preloader short).
# Static link the runtime so no MinGW DLLs are dragged into the wine prefix.
"$CXX" -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter \
    -static -static-libgcc -static-libstdc++ \
    -Wl,--subsystem,windows -Wl,--no-insert-timestamp \
    -I. \
    -o "$OUT_FILE" \
    src/main.cpp clean_shutdown.cpp \
    -ladvapi32 -lkernel32 -luser32 -lws2_32

"$STRIP" "$OUT_FILE"

echo "Built: $OUT_FILE  ($(stat -c '%s' "$OUT_FILE") bytes)"
file "$OUT_FILE"

CXX32="${CXX32:-i686-w64-mingw32-g++-posix}"
STRIP32="${STRIP32:-i686-w64-mingw32-strip}"
OUT_FILE32="../../assets/ludashisteam/bionic/steam32.exe"

if command -v "$CXX32" >/dev/null 2>&1; then
    "$CXX32" -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter \
        -static -static-libgcc -static-libstdc++ \
        -Wl,--subsystem,windows -Wl,--no-insert-timestamp \
        -I. \
        -o "$OUT_FILE32" \
        src/main.cpp clean_shutdown.cpp \
        -ladvapi32 -lkernel32 -luser32 -lws2_32
    "$STRIP32" "$OUT_FILE32"
    echo "Built: $OUT_FILE32  ($(stat -c '%s' "$OUT_FILE32") bytes)"
    file "$OUT_FILE32"
else
    echo "SKIPPED 32-bit build: $CXX32 not installed"
fi

if [[ "${BUILD_PROBES:-0}" != "1" ]]; then exit 0; fi

PROBE_OUT="../../assets/ludashisteam/bionic/ludashi-iface-probe.exe"
"$CXX" -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter \
    -static -static-libgcc -static-libstdc++ \
    -I. \
    -o "$PROBE_OUT" \
    src/iface_probe.cpp \
    -lkernel32 -lcrypt32 -liphlpapi
"$STRIP" "$PROBE_OUT"
echo "Built: $PROBE_OUT  ($(stat -c '%s' "$PROBE_OUT") bytes)"
file "$PROBE_OUT"

PROBE32_OUT="../../assets/ludashisteam/bionic/ludashi-iface-probe32.exe"
if command -v "$CXX32" >/dev/null 2>&1; then
    "$CXX32" -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter \
        -static -static-libgcc -static-libstdc++ \
        -I. \
        -o "$PROBE32_OUT" \
        src/iface_probe.cpp \
        -lkernel32 -lcrypt32 -liphlpapi
    "$STRIP32" "$PROBE32_OUT"
    echo "Built: $PROBE32_OUT  ($(stat -c '%s' "$PROBE32_OUT") bytes)"
    file "$PROBE32_OUT"
else
    echo "SKIPPED 32-bit probe: $CXX32 not installed"
fi
