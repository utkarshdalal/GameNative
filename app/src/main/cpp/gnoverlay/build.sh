#!/bin/sh
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/../../../.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.3.13750724}"
HOST="$(ls "$NDK/toolchains/llvm/prebuilt" | head -n 1)"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
OUT="$HERE/out/bionic"
mkdir -p "$OUT"

"$BIN/clang" --target=aarch64-linux-android29 \
    -std=gnu11 -O2 -fPIC -shared -fvisibility=hidden \
    -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -Wall -Wextra \
    -o "$OUT/libgnoverlay.so" \
    "$HERE/gnoverlay_core.c" "$HERE/gnoverlay_hooks.c" \
    -ldl -llog \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--no-undefined
"$BIN/llvm-strip" --strip-unneeded "$OUT/libgnoverlay.so"

DEST="$APP/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
cp "$OUT/libgnoverlay.so" "$DEST/libgnoverlay.so"
echo "built $OUT/libgnoverlay.so -> $DEST/libgnoverlay.so"
