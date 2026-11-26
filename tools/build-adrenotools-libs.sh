#!/usr/bin/env bash
# Builds the three prebuilt libraries that contain adrenotools (and its linkernsbypass copy)
# and installs them into the modern and legacy jniLibs:
#   libextras.so, libhook_impl.so   from app/src/main/cpp/extras/CMakeLists.txt
#   libvulkan_renderer.so           from app/src/main/cpp/CMakeLists.txt
# Gradle does not build these; the committed files in jniLibs are what ships. Run this script
# after a change to extras/, winlator/Vulkan*, lsfg/, the adrenotools submodule pin or
# extras/adrenotools-patches/, and commit the result.
#
# adrenotools is built from a copy of the submodule with extras/adrenotools-patches/*.patch
# applied (extras/adrenotools-patched.cmake). A patch that no longer applies stops the build.
#
# Prerequisites:
#   - git submodule update --init --recursive app/src/main/cpp/extras/adrenotools app/src/main/cpp/lsfg-vk-android
#   - cmake, ninja, patch, glslangValidator on PATH
#   - Android NDK 27.3.13750724 (matches ndkVersion in app/build.gradle.kts); set ANDROID_NDK_HOME
#     or let the script probe the default SDK locations.
#
# Build settings match the committed libvulkan_renderer.so: API 26, static libc++, 16 KB pages,
# Release. With an empty patch directory the output disassembles the same as the files this
# script replaced.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$REPO/app/src/main/cpp"
NDK_VERSION="27.3.13750724"
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
    for candidate in "$HOME/Library/Android/sdk/ndk/$NDK_VERSION" "$HOME/Android/Sdk/ndk/$NDK_VERSION"; do
        if [ -d "$candidate" ]; then NDK="$candidate"; break; fi
    done
fi
[ -d "$NDK" ] || { echo "Android NDK $NDK_VERSION not found; set ANDROID_NDK_HOME" >&2; exit 1; }

BUILD="${ADRENOTOOLS_LIBS_BUILD_DIR:-$(mktemp -d)}"

configure_and_build() {
    local source="$1" build="$2"; shift 2
    cmake -S "$source" -B "$build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-26 \
        -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=Release
    ninja -C "$build" "$@"
}

configure_and_build "$CPP/extras" "$BUILD/extras" extras hook_impl
configure_and_build "$CPP" "$BUILD/main" vulkan_renderer

for flavor in modern legacy; do
    dest="$REPO/app/src/$flavor/jniLibs/arm64-v8a"
    cp "$BUILD/extras/libextras.so" "$dest/libextras.so"
    cp "$BUILD/extras/adrenotools-build/src/hook/libhook_impl.so" "$dest/libhook_impl.so"
    cp "$BUILD/main/libvulkan_renderer.so" "$dest/libvulkan_renderer.so"
done

echo "Installed libextras.so, libhook_impl.so and libvulkan_renderer.so into the modern and legacy jniLibs"
