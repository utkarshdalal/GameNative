cmake_minimum_required(VERSION 3.22.1)

Project(Pluvia)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -s")

#add_subdirectory(proot)
add_subdirectory(virglrenderer)
add_subdirectory(lsfg-vk-android/thirdparty/dxbc EXCLUDE_FROM_ALL)
add_subdirectory(patchelf)
add_subdirectory(extras/adrenotools)

add_library(winlator SHARED
            winlator/drawable.c
            winlator/sysvshared_memory.c
            winlator/xconnector_epoll.c
            winlator/alsa_client.c
            winlator/patchelf_wrapper.cpp)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      aaudio
                      EGL
                      GLESv2
                      GLESv3)

target_link_options(winlator PRIVATE -Wl,-z,max-page-size=16384)


add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
    COMMAND glslangValidator -V --vn window_vert_code -x
            -o ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
            ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.vert
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.vert
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
    COMMAND glslangValidator -V --vn window_frag_code -x
            -o ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
            ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.frag
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.frag
)

add_library(vulkan_renderer SHARED
            winlator/vulkan_jni.cpp
            winlator/VulkanRendererContext.cpp
            winlator/VulkanRendererScanout.cpp
            winlator/VulkanRendererLsfg.cpp
            lsfg/vk_dispatch.c
            lsfg/lsfg_dll.c
            lsfg/lsfg_dxbc.cpp
            lsfg/lsfg_probe.c
            lsfg/lsfg_jni.c
            lsfg/lsfg_common.cpp
            lsfg/lsfg_shaders.cpp
            lsfg/lsfg_mipmaps.cpp
            lsfg/lsfg_generate.cpp
            lsfg/lsfg_alpha.cpp
            lsfg/lsfg_beta.cpp
            lsfg/lsfg_gamma.cpp
            lsfg/lsfg_delta.cpp
            lsfg/lsfg_chain.cpp
            lsfg/lsfg_pacer.cpp
            lsfg/vkr_lsfg.cpp
            ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
            ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h)

target_include_directories(vulkan_renderer PRIVATE
                           ${CMAKE_CURRENT_BINARY_DIR}
                           ${CMAKE_CURRENT_SOURCE_DIR}/winlator
                           ${CMAKE_CURRENT_SOURCE_DIR}/lsfg)

set_target_properties(vulkan_renderer PROPERTIES
                      CXX_STANDARD 17
                      CXX_STANDARD_REQUIRED ON)

target_compile_options(vulkan_renderer PRIVATE
                       -Wall -Wextra -fvisibility=hidden)

target_link_libraries(vulkan_renderer
                      log
                      android
                      vulkan
                      adrenotools
                      dxbc
                      dl
                      atomic)

target_link_options(vulkan_renderer PRIVATE -Wl,-z,max-page-size=16384)
