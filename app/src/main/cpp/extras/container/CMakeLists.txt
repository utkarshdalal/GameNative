cmake_minimum_required(VERSION 3.22.1)

# The copies of libadrenotools.so and libhook_impl.so that game processes load from the container
# (imagefs/usr/lib). They ship inside the wrapper*.tzst graphics driver archives, not in the APK.
# Built by tools/build-adrenotools-libs.sh.
project(gn_container_adrenotools LANGUAGES C CXX)

include(${CMAKE_CURRENT_LIST_DIR}/../adrenotools-patched.cmake)

file(WRITE ${CMAKE_BINARY_DIR}/empty.cpp "")
add_library(adrenotools_shared SHARED ${CMAKE_BINARY_DIR}/empty.cpp)
set_target_properties(adrenotools_shared PROPERTIES OUTPUT_NAME adrenotools)
target_link_libraries(adrenotools_shared PRIVATE
        -Wl,--whole-archive adrenotools -Wl,--no-whole-archive
        linkernsbypass android log)

# Export only the adrenotools and hook entry points, as the libraries in the archives always did
target_link_options(adrenotools_shared PRIVATE
        -Wl,--exclude-libs,liblinkernsbypass.a
        -Wl,--exclude-libs,libc++_static.a
        -Wl,--exclude-libs,libc++abi.a)
target_link_options(hook_impl PRIVATE -Wl,--exclude-libs,ALL)
