#!/usr/bin/env bash
# Builds the three prebuilt libraries that contain adrenotools (and its linkernsbypass copy)
# and installs them into the modern and legacy jniLibs:
#   libextras.so, libhook_impl.so   from app/src/main/cpp/extras/CMakeLists.txt
#   libvulkan_renderer.so           from app/src/main/cpp/CMakeLists.txt
#
# With --wrappers <version> it also builds the container copies (libadrenotools.so and
# libhook_impl.so from extras/container/CMakeLists.txt) and repacks the five legacy assets
# graphics_driver/wrapper*.tzst with them; nothing else in an archive changes. The same files
# are then uploaded as graphics_driver/<id>-<version>.tzst and named in
# app/src/main/assets/graphics_driver_download.json, because game processes load these copies
# from the container, not the ones in the APK.
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
# Build settings: API 26, static libc++, 16 KB pages, Release. With an empty patch directory
# libvulkan_renderer.so disassembles the same as the modern file this script first replaced.
# The modern libextras.so and libhook_impl.so it replaced were API 29 builds of the same sources
# (the same check passes at API 29); they are now API 26 so both flavors share one file.
#
# Nothing in Gradle or CI runs this script or checks the patches. After a submodule update the
# old binaries keep shipping until this script is run and its output committed.
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

WRAPPERS_VERSION=""
if [ "${1:-}" = "--wrappers" ]; then
    WRAPPERS_VERSION="${2:?--wrappers needs a version, for example 20261127}"
fi

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

if [ -n "$WRAPPERS_VERSION" ]; then
    configure_and_build "$CPP/extras/container" "$BUILD/container" adrenotools_shared hook_impl
    STRIP="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)/llvm-strip"
    CONTAINER_ADRENOTOOLS="$BUILD/container/libadrenotools.so"
    CONTAINER_HOOK_IMPL="$BUILD/container/adrenotools-build/src/hook/libhook_impl.so"
    "$STRIP" --strip-unneeded "$CONTAINER_ADRENOTOOLS" "$CONTAINER_HOOK_IMPL"

    ASSETS="$REPO/app/src/legacy/assets/graphics_driver"
    UPLOAD="$BUILD/wrappers-$WRAPPERS_VERSION"
    mkdir -p "$UPLOAD"
    for id in wrapper wrapper-v2 wrapper-gamenative wrapper-legacy wrapper-leegao; do
        work="$BUILD/repack-$id"
        rm -rf "$work"; mkdir -p "$work"
        zstd -dc "$ASSETS/$id.tzst" | tar -xpf - -C "$work"
        cp "$CONTAINER_ADRENOTOOLS" "$work/usr/lib/libadrenotools.so"
        cp "$CONTAINER_HOOK_IMPL" "$work/usr/lib/libhook_impl.so"
        chmod 755 "$work/usr/lib/libadrenotools.so" "$work/usr/lib/libhook_impl.so"
        (cd "$work" && COPYFILE_DISABLE=1 tar --no-xattrs -cf - usr | zstd -q -19 -f -o "$UPLOAD/$id-$WRAPPERS_VERSION.tzst")
        cp "$UPLOAD/$id-$WRAPPERS_VERSION.tzst" "$ASSETS/$id.tzst"
    done
    echo "Repacked the legacy wrapper assets; upload the files in $UPLOAD as graphics_driver/<name>"
fi
