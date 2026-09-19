cmake_minimum_required(VERSION 3.10)

project(dxbc
    VERSION 2.6.2
    DESCRIPTION "DXVK's DXBC"
    LANGUAGES CXX)

file(GLOB SOURCES
    "src/dxbc/*.cpp"
    "src/spirv/*.cpp"
    "src/util/*.cpp"
)

add_library(dxbc STATIC ${SOURCES})

# target
set_target_properties(dxbc PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON)
target_include_directories(dxbc SYSTEM
    PUBLIC include/dxbc
    PUBLIC include/spirv include/util include/dxvk)
target_compile_options(dxbc PRIVATE
    -fPIC)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set_target_properties(dxbc PROPERTIES
        EXPORT_COMPILE_COMMANDS ON)
endif()