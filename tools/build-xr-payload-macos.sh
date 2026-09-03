#!/bin/bash
# Builds the complete Windows-VR payload on macOS into app/src/modernXr/assets.
#
#   tools/build-xr-payload-macos.sh            # runtime DLLs + unixlib (the usual iteration loop)
#   tools/build-xr-payload-macos.sh --bridge   # also the arm64x Wine builtin (Docker; slow first run)
#
# After this, `./gradlew assembleModernXrDebug` packages everything.
set -eu

repository="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ndk="$sdk/ndk/27.3.13750724"
bin="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"
cmake="$sdk/cmake/3.22.1/bin/cmake"
ninja="$sdk/cmake/3.22.1/bin/ninja"
source_dir="$repository/app/src/main/windows/openxr_runtime"
work="$repository/app/build/xr-payload-macos"
output="$repository/app/src/modernXr/assets"
mkdir -p "$work" "$output"

# OpenXR headers (pinned to the same release the Windows build uses).
if [ ! -f "$work/inc/openxr/openxr.h" ]; then
    mkdir -p "$work/inc/openxr"
    curl -sL https://github.com/KhronosGroup/OpenXR-SDK/archive/refs/tags/release-1.1.61.tar.gz \
        | tar -xz -C "$work" --strip-components=2 "OpenXR-SDK-release-1.1.61/include/openxr"
    mv "$work/openxr/"*.h "$work/inc/openxr/" 2>/dev/null || true
    cp -R "$work"/*.h "$work/inc/openxr/" 2>/dev/null || true
    [ -f "$work/inc/openxr/openxr.h" ] || { echo "OpenXR headers missing"; exit 1; }
fi

# Import libraries from the .def files.
for lib in ws2_32 kernel32 ntdll dxgi; do
    "$bin/llvm-dlltool" -m i386:x86-64 -d "$source_dir/${lib}_x64.def" -l "$work/lib${lib}_x64.a"
    "$bin/llvm-dlltool" -m i386 -k -d "$source_dir/${lib}_x86.def" -l "$work/lib${lib}_x86.a"
done

# The two OpenXR runtime DLLs (CRT-less PE, built with the NDK's clang).
"$bin/clang" --target=x86_64-w64-windows-gnu -shared -nostdlib -Wl,-e,DllMain -I "$work/inc" \
    -o "$output/gamenative_openxr_runtime64.dll" \
    "$source_dir/gamenative_openxr_runtime.c" "$source_dir/gamenative_openxr_runtime_x64.def" \
    "$work/libws2_32_x64.a" "$work/libkernel32_x64.a" "$work/libntdll_x64.a" "$work/libdxgi_x64.a"
"$bin/clang" --target=i686-w64-windows-gnu -shared -nostdlib -Wl,-e,DllMain -I "$work/inc" \
    -o "$output/gamenative_openxr_runtime32.dll" \
    "$source_dir/gamenative_openxr_runtime.c" "$source_dir/gamenative_openxr_runtime_x86.def" \
    "$work/libws2_32_x86.a" "$work/libkernel32_x86.a" "$work/libntdll_x86.a" "$work/libdxgi_x86.a"
hash64=$(shasum -a 256 "$output/gamenative_openxr_runtime64.dll" | cut -d' ' -f1)
hash32=$(shasum -a 256 "$output/gamenative_openxr_runtime32.dll" | cut -d' ' -f1)
printf "3 %s %s" "$hash64" "$hash32" > "$output/payload.version"

# The Wine unixlib (plain NDK shared library).
"$cmake" -S "$source_dir/unix" -B "$work/unixlib" -GNinja \
    -DCMAKE_MAKE_PROGRAM="$ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release >/dev/null
"$cmake" --build "$work/unixlib"
cp "$work/unixlib/gamenative_xr_unixbridge.so" "$output/"

# The 32-bit Wine builtin stub ships prebuilt in the repo.
cp "$source_dir/builtin/gamenative_xr_unixbridge32.dll" "$output/"

# OpenComposite (pinned download, checksum-verified) for OpenVR titles.
if [ ! -f "$output/opencomposite_x64.dll" ]; then
    curl -sL "https://opencomposite.znix.xyz/builds/download_build?artefact_id=5r2yCwgFn_ozJ44c&build_id=49918237&commit=43e551a4506880ab1a71b8b9fec2fd7fbb27372f" \
        -o "$output/opencomposite_x64.dll"
    echo "2f56d45323f252a2ea7c3047c806f9aca3cab36c3f9f71d1dad05a5008b7731a  $output/opencomposite_x64.dll" \
        | shasum -a 256 -c - >/dev/null || { echo "OpenComposite checksum mismatch"; exit 1; }
fi

# The arm64x Wine builtin needs Linux; run it in Docker on request.
# The gn-arm64x volume caches the toolchains and Wine tree, so reruns take minutes.
if [ "${1:-}" = "--bridge" ]; then
    docker run --rm --platform linux/amd64 \
        -v gn-arm64x:/root/gamenative-arm64x -v "$repository":/repo ubuntu:22.04 bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq build-essential flex bison git wget unzip xz-utils python3 perl pkg-config autoconf automake ca-certificates file >/dev/null
mkdir -p /root/Android/Sdk/ndk
cd /repo && bash tools/provision-build-arm64x-wine-bridge.sh /repo'
elif [ ! -f "$output/gamenative_xr_unixbridge.dll" ]; then
    echo "note: arm64x bridge DLL not present — run with --bridge to build it (Docker)"
fi

echo "payload ready:"
ls -la "$output"
