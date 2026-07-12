cmake_minimum_required(VERSION 3.22.1)

project(asurface_renderer)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

function(enable_16kb_pages target_name)
    target_link_options(${target_name} PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384"
    )
endfunction()

# =========================================================
# Global compiler settings
# =========================================================

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# =========================================================
# ASurface Renderer library
# =========================================================

add_library(asurface_renderer SHARED
        asurface_jni.cpp
        ASurfaceRendererContext.cpp
        blit_converter.cpp
)

target_compile_options(asurface_renderer PRIVATE
        -Wall
        -Wextra
        -fvisibility=hidden
)

target_link_libraries(asurface_renderer
        log
        android
        dl
        atomic
        EGL
        GLESv3
)

enable_16kb_pages(asurface_renderer)

# =========================================================
# Drawable library
# =========================================================


add_library(drawable SHARED
        drawable.c
)

target_compile_features(drawable PRIVATE c_std_11)

target_compile_options(drawable PRIVATE
        -O3
        -Wall
        -Wextra
        -Wno-unused-parameter
        -Rpass=loop-vectorize
        -ffast-math
)

# Enable NEON on ARM32 builds
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(drawable PRIVATE -mfpu=neon)
endif()

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(JNIGRAPHICS_LIB jnigraphics)

target_link_libraries(drawable
        ${LOG_LIB}
        ${ANDROID_LIB}
        ${JNIGRAPHICS_LIB}
        m
)

enable_16kb_pages(drawable)

# =========================================================
# AHBImage library
# =========================================================

add_library(ahbimage SHARED
        ahbimage.c
)

target_compile_definitions(ahbimage PRIVATE
        EGL_EGLEXT_PROTOTYPES
        GL_GLEXT_PROTOTYPES
)

target_compile_options(ahbimage PRIVATE
        -Wall
        -Wextra
)

target_link_libraries(ahbimage
        log
        android
        EGL
        GLESv2
)

enable_16kb_pages(ahbimage)
