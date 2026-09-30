cmake_minimum_required(VERSION 3.22)
project(gamenative_openxr_runtime C)

include(FetchContent)
FetchContent_Declare(
    openxr_sdk
    URL https://github.com/KhronosGroup/OpenXR-SDK-Source/archive/refs/tags/release-1.1.61.tar.gz
    URL_HASH SHA256=b88e83e2515cadb08608747b6c0f272a62b04f5089a965288f3aa4a4332291ba
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
set(BUILD_LOADER OFF CACHE BOOL "" FORCE)
set(BUILD_API_LAYERS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_CONFORMANCE_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SDK_TESTS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(openxr_sdk)

add_library(gamenative_openxr_runtime SHARED gamenative_openxr_runtime.c)
target_compile_definitions(gamenative_openxr_runtime PRIVATE XR_USE_PLATFORM_WIN32 XR_USE_GRAPHICS_API_D3D11 WIN32_LEAN_AND_MEAN)
target_include_directories(gamenative_openxr_runtime PRIVATE
    ${openxr_sdk_BINARY_DIR}/include
    ${openxr_sdk_SOURCE_DIR}/include
    ${openxr_sdk_SOURCE_DIR}/src/common
)
target_link_libraries(gamenative_openxr_runtime PRIVATE ws2_32 dxgi)
set_target_properties(gamenative_openxr_runtime PROPERTIES OUTPUT_NAME gamenative_openxr_runtime64)
if(MSVC)
    target_compile_options(gamenative_openxr_runtime PRIVATE /W4 /WX /wd4201 /guard:cf)
    target_link_options(gamenative_openxr_runtime PRIVATE /DEF:${CMAKE_CURRENT_SOURCE_DIR}/gamenative_openxr_runtime_x64.def)
endif()
