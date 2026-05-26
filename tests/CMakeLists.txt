cmake_minimum_required(VERSION 3.22.1)
project(WinlatorTests CXX C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# ── RapidCheck via FetchContent ───────────────────────────────────────────────
include(FetchContent)

FetchContent_Declare(
    rapidcheck
    GIT_REPOSITORY https://github.com/emil-e/rapidcheck.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
)
set(RC_ENABLE_GTEST ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(rapidcheck)

# ── GoogleTest via FetchContent ───────────────────────────────────────────────
FetchContent_Declare(
    googletest
    GIT_REPOSITORY https://github.com/google/googletest.git
    GIT_TAG        v1.14.0
    GIT_SHALLOW    TRUE
)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

# ── Mock NDK headers ──────────────────────────────────────────────────────────
# The mock headers are in tests/mock_ndk/ and provide stub implementations
# of Android NDK APIs (AHardwareBuffer, android/log.h, sync/sync.h, jni.h)
# so that ahb_bridge.c can be compiled and tested on the host.

set(MOCK_NDK_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/mock_ndk")

# ── Property 13: JNI Bridge Error Sentinel ────────────────────────────────────
add_executable(test_ahb_bridge_property13
    prop13_jni_bridge_error_sentinel.cpp
    mock_ndk_impl.cpp
    # Compile ahb_bridge.c with mocked NDK headers
    "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/winlator/ahb_bridge.c"
)

target_include_directories(test_ahb_bridge_property13 PRIVATE
    "${MOCK_NDK_INCLUDE_DIR}"
)

target_compile_definitions(test_ahb_bridge_property13 PRIVATE
    # Tell the mock headers to use the controllable failure injection
    AHB_BRIDGE_TEST_MODE=1
    # Suppress MSVC deprecation warnings for strncpy, vsnprintf, etc.
    $<$<CXX_COMPILER_ID:MSVC>:_CRT_SECURE_NO_WARNINGS>
)

target_compile_options(test_ahb_bridge_property13 PRIVATE
    # Suppress warnings from the mock headers and test code
    $<$<CXX_COMPILER_ID:MSVC>:/W3 /wd4100 /wd4505>
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers>
)

target_link_libraries(test_ahb_bridge_property13 PRIVATE
    rapidcheck
    rapidcheck_gtest
    GTest::gtest_main
)

include(CTest)
enable_testing()
include(GoogleTest)
gtest_discover_tests(test_ahb_bridge_property13)
