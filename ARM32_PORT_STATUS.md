# ARM32 port status

This branch is an **experimental ARM32 bring-up**, not a finished or installable Winlator ARM32 release.

## Work included in this prototype

- The Gradle build can select `armeabi-v7a` with `-PwinlatorAbi=armeabi-v7a`.
- The optional Rust Steam client now selects the matching Rust target:
  - `aarch64-linux-android` for `arm64-v8a`
  - `armv7-linux-androideabi` for `armeabi-v7a`
- A JNI-only native compile mode is available with `-PwinlatorNativeCompileOnly=true`. It deliberately skips downloading/package-staging the ARM64 runtime payloads.

## Why this is not yet a usable ARM32 APK

The current application runtime is still built around 64-bit components:

1. The normal build downloads a Proton **ARM64EC** package. ARM64EC/FEX runtime payloads cannot be treated as 32-bit ARMv7 binaries.
2. The component-tools manifest and published helper libraries currently target `arm64-v8a`; there is no matching `armeabi-v7a` manifest in this repository.
3. The project does not include an ARMHF Linux root filesystem plus a matching 32-bit Wine/Box86 runtime.
4. JNI build success alone would only show that selected native source files compile. It does not prove Windows applications can start inside a container.

For these reasons, a normal ARM32 APK build is intentionally stopped with a clear Gradle error instead of packaging a misleading APK with missing or wrong-architecture runtime files.

## Native compile probe

Install Android NDK `29.0.14206865`, CMake `3.22.1`, JDK 17, and Rust stable with target `armv7-linux-androideabi`, then run:

```bash
./gradlew :app:externalNativeBuildDebug \
  -PwinlatorAbi=armeabi-v7a \
  -PwinlatorNativeCompileOnly=true
```

The GitHub Actions workflow `ARM32 native compile probe` runs this check automatically on this branch.

## Remaining milestones before an installable port

- Build and verify every required JNI/native library for `armeabi-v7a`.
- Provide ARMv7 versions of all bundled helper libraries, each with recorded SHA-256 checksums.
- Prepare an ARMHF Linux userspace, 32-bit Wine and a compatible Box86 runtime.
- Adapt container creation/runtime selection so ARM32 devices never select ARM64EC/FEX packages.
- Build the APK, inspect its native library directories, and test installation and container startup on a real ARM32 Android device.

No claim of runtime compatibility should be made until those checks pass.
