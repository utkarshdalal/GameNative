cmake_minimum_required(VERSION 3.22.1)

project(SteamBootstrap C)

add_library(steambootstrap SHARED steam_bootstrap.c)

target_compile_options(steambootstrap PRIVATE
    -fvisibility=hidden
    -fno-asynchronous-unwind-tables
    -fno-unwind-tables
    -ffunction-sections
    -fdata-sections
    -fomit-frame-pointer
    -fno-stack-protector
    -fno-ident
    -fmerge-all-constants
    -O3
    -flto)

target_link_options(steambootstrap PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--strip-all
    -Wl,--discard-all
    -flto
    "$<$<NOT:$<CONFIG:Debug>>:-Wl,-s>"
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

target_link_libraries(steambootstrap log dl)
