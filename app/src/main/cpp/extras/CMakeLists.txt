cmake_minimum_required(VERSION 3.22.1)

Project(Pluvia)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -s")

add_subdirectory(adrenotools)

add_library(extras SHARED
        gpu_image.c
        vulkan.c)

target_link_libraries(extras
        log
        android
        EGL
        GLESv2
        GLESv3
        adrenotools)

target_link_options(extras PRIVATE -Wl,-z,max-page-size=16384)
