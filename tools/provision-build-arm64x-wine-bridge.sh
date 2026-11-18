#!/bin/bash
set -eu

repository="$1"
base="${GAMENATIVE_ARM64X_HOME:-$HOME/gamenative-arm64x}"
wine_commit="77eed5501e077f5f0422d063b171a3a4491f52a6"
llvm_archive="llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64.tar.xz"
llvm_sha="8dd8c34fc051a50c2fae86015f35057f8aae93fe1e19b34537ef1269a8b4c772"
ndk_archive="android-ndk-r27d-linux.zip"
ndk_sha="601246087a682d1944e1e16dd85bc6e49560fe8b6d61255be2829178c8ed15d9"
downloads="$base/downloads"
toolchains="$base/toolchains"
wine_source="$base/wine-arm64x-source"
wine_tools="$base/wine-arm64x-tools"
wine_build="$base/wine-arm64x-build"
output="$repository/app/src/modernXr/assets"
llvm="$toolchains/llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64"
ndk="$HOME/Android/Sdk/ndk/27.3.13750724"

mkdir -p "$downloads" "$toolchains" "$HOME/Android/Sdk/ndk" "$output"
if test ! -f "$downloads/$llvm_archive"; then
    wget -q "https://github.com/bylaws/llvm-mingw/releases/download/20250920/$llvm_archive" -O "$downloads/$llvm_archive"
fi
if test ! -f "$downloads/$ndk_archive"; then
    wget -q "https://dl.google.com/android/repository/$ndk_archive" -O "$downloads/$ndk_archive"
fi
printf '%s  %s\n' "$llvm_sha" "$downloads/$llvm_archive" | sha256sum -c -
printf '%s  %s\n' "$ndk_sha" "$downloads/$ndk_archive" | sha256sum -c -
if test ! -x "$llvm/bin/clang"; then
    tar -xJf "$downloads/$llvm_archive" -C "$toolchains"
fi
if test ! -x "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang"; then
    unzip -q "$downloads/$ndk_archive" -d "$HOME/Android/Sdk/ndk"
    rm -rf "$ndk"
    mv "$HOME/Android/Sdk/ndk/android-ndk-r27d" "$ndk"
fi
if test ! -d "$wine_source/.git"; then
    git clone --filter=blob:none --depth 1 --branch build-p10-20260503-sdk28 https://github.com/GameNative/proton-wine.git "$wine_source"
fi
test "$(git -C "$wine_source" rev-parse HEAD)" = "$wine_commit"
module="$wine_source/dlls/gamenative_xr_unixbridge"
mkdir -p "$module"
cp "$repository/app/src/main/windows/openxr_runtime/builtin/Makefile.in" "$module/Makefile.in"
cp "$repository/app/src/main/windows/openxr_runtime/builtin/gamenative_xr_unixbridge.c" "$module/gamenative_xr_unixbridge.c"
cp "$repository/app/src/main/windows/openxr_runtime/builtin/gamenative_xr_unixbridge.spec" "$module/gamenative_xr_unixbridge.spec"
if ! grep -q 'WINE_CONFIG_MAKEFILE(dlls/gamenative_xr_unixbridge)' "$wine_source/configure.ac"; then
    sed -i '/WINE_CONFIG_MAKEFILE(dlls\/gameinput)/aWINE_CONFIG_MAKEFILE(dlls/gamenative_xr_unixbridge)' "$wine_source/configure.ac"
fi
if test ! -f "$wine_source/.gamenative-arm64x-autogen"; then
    (cd "$wine_source" && ./autogen.sh --aarch64 && touch .gamenative-arm64x-autogen)
fi
if test ! -x "$wine_tools/tools/winebuild/winebuild"; then
    mkdir -p "$wine_tools"
    (cd "$wine_tools" && "$wine_source/configure" --enable-win64 --without-x --without-alsa --without-capi --without-coreaudio --without-cups --without-dbus --without-ffmpeg --without-fontconfig --without-freetype --without-gcrypt --without-gettext --without-gphoto --without-gnutls --without-gssapi --without-gstreamer --without-inotify --without-krb5 --without-netapi --without-opencl --without-opengl --without-osmesa --without-oss --without-pcap --without-pcsclite --without-piper --without-pulse --without-sane --without-sdl --without-udev --without-unwind --without-usb --without-v4l2 --without-vosk --without-vulkan --without-wayland)
    make -C "$wine_tools" -j"$(nproc)" __tooldeps__ nls/all
fi
target_tools="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
mingw_tools="$llvm/bin"
export PATH="$mingw_tools:$PATH"
export CC="$target_tools/aarch64-linux-android28-clang"
export CXX="$target_tools/aarch64-linux-android28-clang++"
export AS="$CC"
export AR="$target_tools/llvm-ar"
export RANLIB="$target_tools/llvm-ranlib"
export STRIP="$target_tools/llvm-strip"
export DLLTOOL="$mingw_tools/llvm-dlltool"
if test ! -f "$wine_build/config.status"; then
    mkdir -p "$wine_build"
    (cd "$wine_build" && "$wine_source/configure" \
        --enable-archs=arm64ec,aarch64 \
        --host=aarch64-linux-android28 \
        --with-mingw=clang \
        --with-wine-tools="$wine_tools" \
        --enable-win64 \
        --disable-win16 \
        --disable-tests \
        --without-x \
        --without-alsa \
        --without-capi \
        --without-coreaudio \
        --without-cups \
        --without-dbus \
        --without-ffmpeg \
        --without-fontconfig \
        --without-freetype \
        --without-gcrypt \
        --without-gettext \
        --without-gphoto \
        --without-gnutls \
        --without-gssapi \
        --without-gstreamer \
        --without-inotify \
        --without-krb5 \
        --without-netapi \
        --without-opencl \
        --without-opengl \
        --without-osmesa \
        --without-oss \
        --without-pcap \
        --without-pcsclite \
        --without-piper \
        --with-pthread \
        --without-pulse \
        --without-sane \
        --without-sdl \
        --without-udev \
        --without-unwind \
        --without-usb \
        --without-v4l2 \
        --without-vosk \
        --without-vulkan \
        --without-wayland)
else
    (cd "$wine_build" && ./config.status)
fi
make -C "$wine_build" -j"$(nproc)" dlls/gamenative_xr_unixbridge/all
artifact="$wine_build/dlls/gamenative_xr_unixbridge/aarch64-windows/gamenative_xr_unixbridge.dll"
test -f "$artifact"
cp "$artifact" "$output/gamenative_xr_unixbridge.dll"
printf '%s\n' "$wine_commit" > "$output/gamenative_xr_unixbridge.wine-commit"
"$llvm/bin/llvm-readobj" --file-headers --coff-load-config "$artifact"
