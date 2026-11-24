#!/usr/bin/env bash
set -euo pipefail

: "${BOX86_COMMIT:?Set BOX86_COMMIT to a pinned upstream Box86 commit}"

WORK_DIR="${RUNNER_TEMP:-/tmp}/winlator-armhf-box86"
SOURCE_DIR="$WORK_DIR/box86"
BUILD_DIR="$WORK_DIR/build"
STAGE_DIR="$WORK_DIR/stage"
DIST_DIR="${GITHUB_WORKSPACE:-$PWD}/dist"

rm -rf "$WORK_DIR" "$DIST_DIR"
mkdir -p "$WORK_DIR" "$STAGE_DIR" "$DIST_DIR"

git clone https://github.com/ptitSeb/box86.git "$SOURCE_DIR"
git -C "$SOURCE_DIR" checkout --detach "$BOX86_COMMIT"

# This is a GNU/Linux ARM hard-float executable for the ARMHF guest rootfs,
# not an Android/Bionic JNI library.
cmake -S "$SOURCE_DIR" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_SYSTEM_NAME=Linux \
  -DCMAKE_SYSTEM_PROCESSOR=armhf \
  -DCMAKE_C_COMPILER=arm-linux-gnueabihf-gcc \
  -DCMAKE_ASM_COMPILER=arm-linux-gnueabihf-gcc \
  -DCMAKE_FIND_ROOT_PATH=/usr/arm-linux-gnueabihf \
  -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY \
  -DCMAKE_C_FLAGS="-marm -march=armv7-a -mfpu=neon -mfloat-abi=hard" \
  -DCMAKE_ASM_FLAGS="-marm -march=armv7-a -mfpu=neon -mfloat-abi=hard" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$STAGE_DIR" \
  -DARM_DYNAREC=ON \
  -DNOGIT=OFF

cmake --build "$BUILD_DIR" --target box86 --parallel 2
cmake --install "$BUILD_DIR"

BOX86_BIN="$STAGE_DIR/bin/box86"
test -x "$BOX86_BIN"
test -s "$STAGE_DIR/etc/box86.box86rc"
test -d "$STAGE_DIR/usr/lib/box86-i386-linux-gnu"

readelf -h "$BOX86_BIN" > "$DIST_DIR/box86-elf-header.txt"
readelf -l "$BOX86_BIN" > "$DIST_DIR/box86-elf-program-headers.txt"
readelf -A "$BOX86_BIN" > "$DIST_DIR/box86-arm-attributes.txt"

grep -q "Machine:.*ARM" "$DIST_DIR/box86-elf-header.txt"
grep -q "ld-linux-armhf.so.3" "$DIST_DIR/box86-elf-program-headers.txt"
grep -q "Tag_ABI_VFP_args: VFP registers" "$DIST_DIR/box86-arm-attributes.txt"

mkdir -p "$DIST_DIR/runtime"
cp -a "$STAGE_DIR/." "$DIST_DIR/runtime/"
cp "$SOURCE_DIR/LICENSE" "$DIST_DIR/runtime/BOX86-LICENSE"
cat > "$DIST_DIR/runtime/BUILD-MANIFEST.txt" <<EOF
Component: Box86
Target: armhf-linux-gnueabihf
CPU baseline: ARMv7-A + NEON
Dynarec: enabled
Upstream repository: https://github.com/ptitSeb/box86
Upstream commit: $BOX86_COMMIT
Build type: Release
Note: component archive only; it is not a complete Wine/rootfs runtime.
EOF

tar --sort=name --numeric-owner --owner=0 --group=0 \
  -C "$DIST_DIR/runtime" -I "zstd -19 -T0" \
  -cf "$DIST_DIR/box86-armhf-runtime.tar.zst" .

sha256sum "$DIST_DIR/box86-armhf-runtime.tar.zst" \
  > "$DIST_DIR/box86-armhf-runtime.tar.zst.sha256"

printf "Box86 ARMHF build verified. Artifact: %s\n" \
  "$DIST_DIR/box86-armhf-runtime.tar.zst"
