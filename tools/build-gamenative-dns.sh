#!/usr/bin/env bash
# Builds libgamenative_dns_v4mapped.so (the LD_PRELOAD DNS shim for bionic Wine
# containers) for arm64 and installs it into app/src/main/jniLibs, which is what
# the app packages.
#
# Prerequisites:
#   - Android NDK r27c (matches ndkVersion in app/build.gradle.kts); set
#     ANDROID_NDK_HOME or let the script probe $HOME/Android/Sdk/ndk/27.3.13750724.
set -euo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.3.13750724}"
HOST_TAG="${HOST_TAG:-$(ls "$NDK/toolchains/llvm/prebuilt" | head -n 1)}"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
src_dir="$APP/app/src/main/cpp"
out="$APP/app/src/main/jniLibs/arm64-v8a/libgamenative_dns_v4mapped.so"

# Compile from the source directory so the artifact records a plain file name.
cd "$src_dir"
"$BIN/clang" --target=aarch64-linux-android26 -shared -fPIC -O2 -Wall \
    -Wl,-z,max-page-size=16384 \
    -o "$out" gamenative_dns_v4mapped.c -ldl

echo "built: $out"
