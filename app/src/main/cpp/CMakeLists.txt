cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(OpenXR-SDK)
add_subdirectory(adrenotools)

# Add Winlator shared library
add_library(winlator SHARED
        xr/engine.c
        xr/framebuffer.c
        xr/input.c
        xr/main.c
        xr/math.c
        xr/renderer.c
        winlator/drawable.c
        winlator/gpu_image.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/xinput_stream.c
        winlator/xoutput_stream.c
        winlator/arrays.c
        winlator/vulkan.c
        winlator/renderer/egl.cpp
        winlator/renderer/renderer_jni.cpp
        winlator/renderer/shader.cpp
        winlator/renderer/window.cpp
        winlator/renderer/cursor.cpp
        winlator/renderer/xform.cpp
        winlator/renderer/view_transformation.cpp
        winlator/renderer/displayx.cpp
        winlator/renderer/effectcomposer/effect_composer.cpp
)

target_compile_options(winlator PRIVATE -Wall -Wextra)
target_include_directories(winlator PRIVATE winlator/renderer winlator/renderer/effectcomposer winlator/include)

target_link_libraries(winlator
        log
        android
        jnigraphics
        openxr_loader
        aaudio
        EGL
        GLESv2
        GLESv3
        adrenotools
        vulkan
)
