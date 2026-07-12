cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -s")

project(XConnectorPatch C)

add_library(xconnectorpatch SHARED xconnectorpatch.c)

target_link_libraries(xconnectorpatch log)

target_link_options(xconnectorpatch PRIVATE -Wl,-z,max-page-size=16384)
