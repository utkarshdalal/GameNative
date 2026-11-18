#!/usr/bin/env bash
# Builds out/libgnaffinity.so (arm64-v8a, stripped, 16 KB pages), then
# cp out/libgnaffinity.so ../../jniLibs/arm64-v8a/
set -euo pipefail
cd "$(dirname "$0")"
NDK=${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/27.3.13750724}
rm -rf build out; mkdir -p out
cmake -S jni -B build -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_C_FLAGS="-ffile-prefix-map=$PWD=."
cmake --build build
HOST=$(ls "$NDK/toolchains/llvm/prebuilt/")
"$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-strip" --strip-all -o out/libgnaffinity.so build/libgnaffinity.so
shasum -a 256 out/libgnaffinity.so
