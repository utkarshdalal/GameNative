cmake_minimum_required(VERSION 3.22.1)

Project(Pluvia)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -s")

#add_subdirectory(proot)
add_subdirectory(virglrenderer)
add_subdirectory(thirdparty/dxbc EXCLUDE_FROM_ALL)
add_subdirectory(patchelf)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_subdirectory(extras/adrenotools)
endif()

add_library(winlator SHARED
            winlator/sysvshared_memory.c
            winlator/xconnector_epoll.c
            winlator/alsa_client.c
            winlator/patchelf_wrapper.cpp
            winlator/vk/lsfg/lsfg_dll.c
            winlator/vk/lsfg/lsfg_dxbc.cpp
            winlator/vk/lsfg/lsfg_probe.c
            winlator/vk/lsfg/lsfg_jni.c)

set_target_properties(winlator PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON)

target_include_directories(winlator PRIVATE winlator/vk/lsfg)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      aaudio
                      EGL
                      GLESv2
                      GLESv3
                      dxbc
                      dl)

target_link_options(winlator PRIVATE -Wl,-z,max-page-size=16384)


if (ANDROID_ABI STREQUAL "arm64-v8a")
    find_program(GLSLANG_VALIDATOR glslangValidator
        HINTS
        "$ENV{ANDROID_SDK_ROOT}/emulator/lib64/vulkan"
        "$ENV{ANDROID_HOME}/emulator/lib64/vulkan"
        "$ENV{LOCALAPPDATA}/Android/Sdk/emulator/lib64/vulkan"
        "$ENV{USERPROFILE}/AppData/Local/Android/Sdk/emulator/lib64/vulkan"
        "$ENV{HOME}/Android/Sdk/emulator/lib64/vulkan"
    )
    if (NOT GLSLANG_VALIDATOR)
        set(GLSLANG_VALIDATOR glslangValidator)
    endif()

    add_custom_command(
        OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
        COMMAND ${GLSLANG_VALIDATOR} -V --vn window_vert_code -x
                -o ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
                ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.vert
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.vert
    )

    add_custom_command(
        OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
        COMMAND ${GLSLANG_VALIDATOR} -V --vn window_frag_code -x
                -o ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
                ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.frag
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.frag
    )

    add_library(vulkan_renderer SHARED
                winlator/vulkan_jni.cpp
                winlator/VulkanRendererContext.cpp
                winlator/VulkanRendererScanout.cpp
                winlator/vk/vk_dispatch.c
                winlator/vk/lsfg/lsfg_dll.c
                winlator/vk/lsfg/lsfg_dxbc.cpp
                winlator/vk/lsfg/lsfg_common.cpp
                winlator/vk/lsfg/lsfg_shaders.cpp
                winlator/vk/lsfg/lsfg_mipmaps.cpp
                winlator/vk/lsfg/lsfg_generate.cpp
                winlator/vk/lsfg/lsfg_alpha.cpp
                winlator/vk/lsfg/lsfg_beta.cpp
                winlator/vk/lsfg/lsfg_gamma.cpp
                winlator/vk/lsfg/lsfg_delta.cpp
                winlator/vk/lsfg/lsfg_chain.cpp
                winlator/vk/lsfg/lsfg_pacer.cpp
                winlator/vk/lsfg/vkr_lsfg.cpp
                ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
                ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h)

    target_include_directories(vulkan_renderer PRIVATE
        ${CMAKE_CURRENT_BINARY_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/winlator
        ${CMAKE_CURRENT_SOURCE_DIR}/winlator/vk
        ${CMAKE_CURRENT_SOURCE_DIR}/winlator/vk/lsfg)

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
endif()
