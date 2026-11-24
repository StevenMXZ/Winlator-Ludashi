# ----------------------------------------------------------------------------
# ludashi-steam-client
# Rust native Steam protocol client for Ludashi.
# ----------------------------------------------------------------------------

cmake_minimum_required(VERSION 3.22.1)

set(LUDASHI_STEAM_CLIENT_VERSION_MAJOR 0)
set(LUDASHI_STEAM_CLIENT_VERSION_MINOR 1)
set(LUDASHI_STEAM_CLIENT_VERSION_PATCH 0)
set(LUDASHI_STEAM_CLIENT_VERSION_STRING
    "${LUDASHI_STEAM_CLIENT_VERSION_MAJOR}.${LUDASHI_STEAM_CLIENT_VERSION_MINOR}.${LUDASHI_STEAM_CLIENT_VERSION_PATCH}")

find_program(CARGO_EXECUTABLE cargo REQUIRED)

if(NOT DEFINED ANDROID_NDK)
    message(FATAL_ERROR "ANDROID_NDK not defined; Rust ludashisteam must be built via Android Gradle/CMake")
endif()

# Rust target triple and NDK clang executable differ for the 32-bit ARM ABI.
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(LUDASHISTEAM_RUST_TARGET "aarch64-linux-android")
    set(LUDASHISTEAM_NDK_CLANG_PREFIX "aarch64-linux-android")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(LUDASHISTEAM_RUST_TARGET "armv7-linux-androideabi")
    set(LUDASHISTEAM_NDK_CLANG_PREFIX "armv7a-linux-androideabi")
else()
    message(FATAL_ERROR "Rust ludashisteam does not support Android ABI: ${CMAKE_ANDROID_ARCH_ABI}")
endif()
string(REPLACE "-" "_" LUDASHISTEAM_RUST_TARGET_ENV "${LUDASHISTEAM_RUST_TARGET}")
string(TOUPPER "${LUDASHISTEAM_RUST_TARGET_ENV}" LUDASHISTEAM_RUST_TARGET_ENV_UPPER)
if(DEFINED ANDROID_PLATFORM)
    string(REGEX REPLACE "^android-" "" LUDASHISTEAM_ANDROID_API "${ANDROID_PLATFORM}")
elseif(DEFINED CMAKE_SYSTEM_VERSION)
    set(LUDASHISTEAM_ANDROID_API "${CMAKE_SYSTEM_VERSION}")
else()
    set(LUDASHISTEAM_ANDROID_API "26")
endif()

if(CMAKE_HOST_WIN32)
    set(LUDASHISTEAM_NDK_HOST_TAG "windows-x86_64")
    set(LUDASHISTEAM_LINKER_SUFFIX ".cmd")
    set(LUDASHISTEAM_AR_SUFFIX ".exe")
elseif(CMAKE_HOST_APPLE)
    set(LUDASHISTEAM_NDK_HOST_TAG "darwin-x86_64")
    set(LUDASHISTEAM_LINKER_SUFFIX "")
    set(LUDASHISTEAM_AR_SUFFIX "")
else()
    set(LUDASHISTEAM_NDK_HOST_TAG "linux-x86_64")
    set(LUDASHISTEAM_LINKER_SUFFIX "")
    set(LUDASHISTEAM_AR_SUFFIX "")
endif()

set(LUDASHISTEAM_RUST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/rust")
file(GLOB_RECURSE LUDASHISTEAM_RUST_SOURCES CONFIGURE_DEPENDS
    "${LUDASHISTEAM_RUST_DIR}/src/*.rs")
set(LUDASHISTEAM_RUST_BUILD_INPUTS
    "${LUDASHISTEAM_RUST_DIR}/Cargo.toml"
    "${LUDASHISTEAM_RUST_DIR}/Cargo.lock"
    ${LUDASHISTEAM_RUST_SOURCES})
set(LUDASHISTEAM_RUST_LIB
    "${LUDASHISTEAM_RUST_DIR}/target/${LUDASHISTEAM_RUST_TARGET}/release/libludashisteam.so")
set(LUDASHISTEAM_RUST_OUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libludashisteam.so")
set(LUDASHISTEAM_RUST_LINKER
    "${ANDROID_NDK}/toolchains/llvm/prebuilt/${LUDASHISTEAM_NDK_HOST_TAG}/bin/${LUDASHISTEAM_NDK_CLANG_PREFIX}${LUDASHISTEAM_ANDROID_API}-clang${LUDASHISTEAM_LINKER_SUFFIX}")

add_custom_command(
    OUTPUT "${LUDASHISTEAM_RUST_OUT}"
    COMMAND "${CMAKE_COMMAND}" -E env
        "CARGO_TARGET_${LUDASHISTEAM_RUST_TARGET_ENV_UPPER}_LINKER=${LUDASHISTEAM_RUST_LINKER}"
        "CC_${LUDASHISTEAM_RUST_TARGET_ENV}=${LUDASHISTEAM_RUST_LINKER}"
        "AR_${LUDASHISTEAM_RUST_TARGET_ENV}=${ANDROID_NDK}/toolchains/llvm/prebuilt/${LUDASHISTEAM_NDK_HOST_TAG}/bin/llvm-ar${LUDASHISTEAM_AR_SUFFIX}"
        "${CARGO_EXECUTABLE}" rustc --locked --release --target "${LUDASHISTEAM_RUST_TARGET}" --lib --
        -Clink-arg=-Wl,-soname,libludashisteam.so
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${LUDASHISTEAM_RUST_LIB}"
        "${LUDASHISTEAM_RUST_OUT}"
    WORKING_DIRECTORY "${LUDASHISTEAM_RUST_DIR}"
    DEPENDS ${LUDASHISTEAM_RUST_BUILD_INPUTS}
    COMMENT "Building Rust ludashisteam for ${LUDASHISTEAM_RUST_TARGET}"
    VERBATIM
)

add_custom_target(ludashisteam_rust_build DEPENDS "${LUDASHISTEAM_RUST_OUT}")
add_library(ludashisteam SHARED IMPORTED GLOBAL)
set_target_properties(ludashisteam PROPERTIES
    IMPORTED_LOCATION "${LUDASHISTEAM_RUST_OUT}"
    IMPORTED_SONAME "libludashisteam.so")
add_dependencies(ludashisteam ludashisteam_rust_build)
