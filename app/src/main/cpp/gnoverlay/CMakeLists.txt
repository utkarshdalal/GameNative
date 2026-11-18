cmake_minimum_required(VERSION 3.22)
project(gnoverlay C)

set(CMAKE_C_STANDARD 11)

add_library(gnoverlay SHARED
        gnoverlay_core.c
        gnoverlay_hooks.c
)

find_library(log-lib  log)
find_library(dl-lib   dl)

target_link_libraries(gnoverlay
        ${log-lib}
        ${dl-lib}
)

target_link_options(gnoverlay PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

target_compile_options(gnoverlay PRIVATE
        -fvisibility=hidden
        -U_FORTIFY_SOURCE
        -D_FORTIFY_SOURCE=0
        -O2
        -Wall
        -Wextra
)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET gnoverlay POST_BUILD
            COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:gnoverlay>
    )
endif()
