#!/bin/bash
# Builds lib7zx.so, an Android executable, from the public-domain LZMA SDK decoder.
set -e
cd "$(dirname "$0")"
NDK=${NDK:-$HOME/Library/Android/sdk/ndk/27.3.13750724}
BIN=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin
SRC="sevenzx.c lzma/7zAlloc.c lzma/7zArcIn.c lzma/7zBuf.c lzma/7zBuf2.c lzma/7zCrc.c lzma/7zCrcOpt.c lzma/7zDec.c lzma/7zStream.c lzma/Bcj2.c lzma/Bra.c lzma/Bra86.c lzma/BraIA64.c lzma/CpuArch.c lzma/Delta.c lzma/Lzma2Dec.c lzma/LzmaDec.c lzma/Ppmd7.c lzma/Ppmd7Dec.c"
$BIN/aarch64-linux-android26-clang -O2 -D_7ZIP_PPMD_SUPPPORT -Wl,-z,max-page-size=16384 -o ../../jniLibs/arm64-v8a/lib7zx.so $SRC
$BIN/llvm-strip ../../jniLibs/arm64-v8a/lib7zx.so
ls -la ../../jniLibs/arm64-v8a/lib7zx.so
