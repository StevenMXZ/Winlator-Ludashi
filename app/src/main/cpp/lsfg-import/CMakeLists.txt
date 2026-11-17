cmake_minimum_required(VERSION 3.22.1)
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(LsfgImport LANGUAGES C CXX)
endif()
include(FetchContent)
FetchContent_Declare(lsfg_dxbc_source
    URL https://codeload.github.com/Username404-59/dxbc/tar.gz/8e25f2614b0f089e0a0d952fcbfe73f7f25f4a3d
    URL_HASH SHA256=0d62224da6260116ee78c07cf5decbac1fef21a329ad13d37b45d33257584cf2)
FetchContent_Declare(lsfg_spirv_headers
    URL https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/aa6cef192b8e693916eb713e7a9ccadf06062ceb
    URL_HASH SHA256=1bf0568bad1f9e30fce8d3199e54ae7ade1c16dbe913b5afd9b499149a9bb9a5)
FetchContent_MakeAvailable(lsfg_spirv_headers)
FetchContent_GetProperties(lsfg_dxbc_source)
if(NOT lsfg_dxbc_source_POPULATED)
    FetchContent_Populate(lsfg_dxbc_source)
endif()
file(GLOB compiler_sources CONFIGURE_DEPENDS
    "${lsfg_dxbc_source_SOURCE_DIR}/src/dxbc/*.cpp"
    "${lsfg_dxbc_source_SOURCE_DIR}/src/spirv/*.cpp"
    "${lsfg_dxbc_source_SOURCE_DIR}/src/util/*.cpp")
set(lsfg_dir ${CMAKE_CURRENT_SOURCE_DIR}/../winlator/renderer/vulkan/lsfg)
add_library(lsfg_dxbc SHARED ${compiler_sources} ${lsfg_dir}/lsfg_dxbc.cpp)
target_compile_features(lsfg_dxbc PRIVATE cxx_std_20)
target_compile_options(lsfg_dxbc PRIVATE -fvisibility=hidden)
target_include_directories(lsfg_dxbc PRIVATE
    ${lsfg_dxbc_source_SOURCE_DIR}/include/dxbc
    ${lsfg_dxbc_source_SOURCE_DIR}/include/dxvk
    ${lsfg_dxbc_source_SOURCE_DIR}/include/spirv
    ${lsfg_dxbc_source_SOURCE_DIR}/include/util
    ${lsfg_spirv_headers_SOURCE_DIR}/include)
if(NOT ANDROID)
    find_path(lsfg_vulkan_include vulkan/vulkan.h HINTS "$ENV{VULKAN_HEADERS}" REQUIRED)
    target_include_directories(lsfg_dxbc PRIVATE ${lsfg_vulkan_include})
    file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/android)
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/android/log.h
        "#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_ERROR 6\nstatic inline int __android_log_print(int p, const char* t, const char* f, ...) { return 0; }\n")
    add_library(lsfg_import_test SHARED ${lsfg_dir}/lsfg_dll.c)
    target_compile_definitions(lsfg_import_test PRIVATE LSFG_DXBC_TRANSLATION=1)
    target_include_directories(lsfg_import_test PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
    target_link_libraries(lsfg_import_test PRIVATE lsfg_dxbc)
endif()
