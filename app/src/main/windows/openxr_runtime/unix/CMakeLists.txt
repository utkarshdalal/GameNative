cmake_minimum_required(VERSION 3.22)
project(gamenative_xr_unixbridge C)

add_library(gamenative_xr_unixbridge SHARED gamenative_openxr_unix.c)
target_include_directories(gamenative_xr_unixbridge PRIVATE ..)
target_link_libraries(gamenative_xr_unixbridge PRIVATE android dl log vulkan)
target_compile_options(gamenative_xr_unixbridge PRIVATE -Wall -Wextra -Werror)
set_target_properties(gamenative_xr_unixbridge PROPERTIES PREFIX "" OUTPUT_NAME "gamenative_xr_unixbridge")
