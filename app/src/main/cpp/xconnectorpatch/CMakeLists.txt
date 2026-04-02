cmake_minimum_required(VERSION 3.22.1)

project(XConnectorPatch C)

add_library(xconnectorpatch SHARED xconnectorpatch.c)

target_link_libraries(xconnectorpatch log)
