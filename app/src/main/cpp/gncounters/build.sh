#!/usr/bin/env bash
# Builds out/libgncounters.so (arm64-v8a, static libc++, libGPUCounters linked in, stripped, 16 KB pages).
# libGPUCounters is cloned into third_party/ at the pinned commit and verified before use.
set -euo pipefail
cd "$(dirname "$0")"
LIBGC_URL=https://github.com/ARM-software/libGPUCounters
LIBGC_TAG=2.5.0
LIBGC_SHA=378c7b59e2dae87cbbffb0ac9cf96ed13c939823
NDK=${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/27.3.13750724}
LIBGC=$PWD/third_party/libGPUCounters
[ -d "$LIBGC/.git" ] || git clone -q "$LIBGC_URL" "$LIBGC"
git -C "$LIBGC" checkout -q "$LIBGC_SHA"
[ "$(git -C "$LIBGC" rev-parse HEAD)" = "$LIBGC_SHA" ] || { echo "libGPUCounters not at $LIBGC_TAG ($LIBGC_SHA)" >&2; exit 1; }
[ -z "$(git -C "$LIBGC" status --porcelain)" ] || { echo "libGPUCounters checkout is modified" >&2; exit 1; }
rm -rf build out; mkdir -p out
cmake -S jni -B build -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DLIBGPUCOUNTERS_DIR="$LIBGC" -DCMAKE_C_FLAGS="-ffile-prefix-map=$PWD=." -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$PWD=."
cmake --build build
HOST=$(ls "$NDK/toolchains/llvm/prebuilt/")
"$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-strip" --strip-all -o out/libgncounters.so build/libgncounters.so
shasum -a 256 out/libgncounters.so
