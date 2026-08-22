# Native OpenXR session for the Meta Quest "immersive launch" mode.
#
# Built against the official Khronos OpenXR loader (org.khronos.openxr:openxr_loader_for_android,
# Apache-2.0, pulled in as a Gradle/prefab dependency in app/build.gradle.kts) — no code or
# binaries from any third-party Winlator VR fork are used here.
#
# NOT wired into the normal Gradle build (see the other commented-out externalNativeBuild
# blocks in app/build.gradle.kts) — this project ships native libs as prebuilt .so files
# committed to jniLibs/, rebuilt manually when needed. To build this module locally:
#   1. Temporarily uncomment the externalNativeBuild { cmake { path = ... } } block for
#      this CMakeLists.txt in app/build.gradle.kts.
#   2. Build, then copy the resulting libxrimmersive.so into
#      app/src/main/jniLibs/arm64-v8a/ and re-comment the block.
cmake_minimum_required(VERSION 3.22.1)
project(xrimmersive)

add_library(xrimmersive SHARED
    xr_immersive.cpp
    jni_bridge.cpp
)

find_package(OpenXR REQUIRED CONFIG)

target_link_libraries(xrimmersive
    OpenXR::openxr_loader
    EGL
    GLESv3
    android
    jnigraphics
    log
)

target_compile_options(xrimmersive PRIVATE -Wall -Wextra -Werror=return-type -Wno-missing-field-initializers)
