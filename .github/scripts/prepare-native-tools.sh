#!/usr/bin/env bash
set -euo pipefail
root="${RUNNER_TEMP:?}/ludashi-ci-tools"
mkdir -p "$root"
fetch() {
    local url="$1" checksum="$2" file="$root/$3"
    if ! printf '%s  %s\n' "$checksum" "$file" | sha256sum --check --status 2>/dev/null; then
        curl --fail --location --retry 2 --connect-timeout 15 --max-time 120 "$url" -o "$file"
    fi
    printf '%s  %s\n' "$checksum" "$file" | sha256sum --check
}
fetch https://github.com/KhronosGroup/glslang/releases/download/16.6.0/glslang-16.6.0-linux-x86_64-release.tar.gz \
    2c34071f56ecf39d16233294d3739cf43f981fd39080f2fd9dccb782101191ce glslang.tar.gz
fetch https://deb.debian.org/debian/pool/main/s/spirv-tools/spirv-tools_2023.1-2_amd64.deb \
    51b1e7352dc84471623d7be38547009bd7b71ffaa1900e512060f8a22503f2dd spirv-tools.deb
fetch https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/refs/tags/v1.4.357 \
    7dc0dbcf1d49dd3d7da3761c251c6097dfbaac475321a4a8a99269d3d5abecdc headers.tar.gz
tar -xzf "$root/glslang.tar.gz" -C "$root"
dpkg-deb -x "$root/spirv-tools.deb" "$root"
tar -xzf "$root/headers.tar.gz" -C "$root"
export PATH="$root/bin:$root/usr/bin:$PATH"
export VULKAN_HEADERS="$root/Vulkan-Headers-1.4.357/include"
printf '%s\n' "$root/bin" "$root/usr/bin" >> "$GITHUB_PATH"
printf 'VULKAN_HEADERS=%s\n' "$VULKAN_HEADERS" >> "$GITHUB_ENV"
for tool in cmake ninja zstd; do command -v "$tool"; done
glslangValidator --version
spirv-val --version
