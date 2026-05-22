cmake_minimum_required(VERSION 3.22.1)

project(SteamBootstrap C)

add_library(steambootstrap SHARED steam_bootstrap.c)

target_compile_options(steambootstrap PRIVATE
    -fvisibility=hidden
    -fno-asynchronous-unwind-tables
    -fno-unwind-tables
    -ffunction-sections
    -fdata-sections)

target_link_options(steambootstrap PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    "$<$<NOT:$<CONFIG:Debug>>:-Wl,--strip-all>")

target_link_libraries(steambootstrap log dl)
