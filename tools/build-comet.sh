#!/usr/bin/env bash
# Builds Comet (https://github.com/imLinguin/comet), the open-source GOG Galaxy
# Communication Service, for GameNative:
#   - app/src/main/jniLibs/arm64-v8a/libcomet.so    aarch64-linux-android executable
#                                                   (run via ProcessBuilder from nativeLibraryDir)
#   - app/src/main/assets/gog/GalaxyCommunication.exe  the dummy Windows service from
#                                                   comet's dummy-service/ (x86_64 PE, static)
#
# Pinned upstream: commit 19b3259 (v0.3.2 + stale overlay-socket recovery, comet#93);
# override with COMET_REF=<tag-or-commit>.
# Patches in tools/comet-patches/*.patch are applied with `git apply`:
#   - paths.rs / import_parsers/heroic.rs: treat target_os="android" like linux
#   - overlay socket path uses std::env::temp_dir() (TMPDIR) instead of /tmp
#   - api/gog/users.rs: token-refresh errors drop the URL (it carries the refresh token)
#   - import_parsers.rs: COMET_ACCESS_TOKEN / COMET_REFRESH_TOKEN / COMET_USER_ID env
#     fallbacks so tokens stay off the command line
#
# Prerequisites:
#   - rustup toolchain >= 1.88 (edition 2024) with the Android target:
#       rustup target add aarch64-linux-android
#     ~/.cargo/bin is put first on PATH so an older Homebrew cargo does not win.
#   - Android NDK r27c (ANDROID_NDK_HOME, else probes the default SDK locations
#     for ndk/27.3.13750724).
#   - x86_64-w64-mingw32-gcc >= 13 and meson + ninja on PATH (brew install mingw-w64 meson).
#     -mcrtdll=msvcrt-os links the exe against msvcrt.dll instead of the UCRT
#     api-ms-win-crt-* forwarders that UCRT-default toolchains (Homebrew) pick.
#   - git and network access (clones comet and its proto submodule).
#   protoc is not needed: comet's build.rs uses protoc-bin-vendored unless PROTOC is set.
set -euo pipefail

COMET_URL="${COMET_URL:-https://github.com/imLinguin/comet.git}"
COMET_REF="${COMET_REF:-19b3259}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH_DIR="$REPO_ROOT/tools/comet-patches"
BUILD_DIR="${COMET_BUILD_DIR:-$REPO_ROOT/build/comet}"
SRC_DIR="$BUILD_DIR/src"
JNI_OUT="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libcomet.so"
EXE_OUT="$REPO_ROOT/app/src/main/assets/gog/GalaxyCommunication.exe"

export PATH="$HOME/.cargo/bin:$PATH"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    for c in "$HOME/Library/Android/sdk/ndk/27.3.13750724" "$HOME/Android/Sdk/ndk/27.3.13750724"; do
        [[ -d "$c" ]] && ANDROID_NDK_HOME="$c" && break
    done
fi
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an NDK r27c install}"
case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64" ;;
    Linux) HOST_TAG="linux-x86_64" ;;
    *) echo "unsupported host $(uname -s)" >&2; exit 1 ;;
esac
NDK_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -x "$NDK_BIN/aarch64-linux-android26-clang" ]] || { echo "NDK clang not found in $NDK_BIN" >&2; exit 1; }

echo "==> comet $COMET_REF into $SRC_DIR"
mkdir -p "$BUILD_DIR"
if [[ ! -d "$SRC_DIR/.git" ]]; then
    git clone --quiet "$COMET_URL" "$SRC_DIR"
fi
git -C "$SRC_DIR" fetch --quiet --tags origin
git -C "$SRC_DIR" checkout --quiet --force "$COMET_REF"
git -C "$SRC_DIR" clean -fdxq -e target
git -C "$SRC_DIR" submodule update --init --recursive
for p in "$PATCH_DIR"/*.patch; do
    echo "    applying $(basename "$p")"
    git -C "$SRC_DIR" apply "$p"
done

echo "==> cargo build --release --target aarch64-linux-android"
export CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android26-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_PROFILE_RELEASE_STRIP=true
(cd "$SRC_DIR" && cargo build --release --locked --bin comet --target aarch64-linux-android)

mkdir -p "$(dirname "$JNI_OUT")"
"$NDK_BIN/llvm-strip" -o "$JNI_OUT" "$SRC_DIR/target/aarch64-linux-android/release/comet"
echo "    installed $JNI_OUT"

echo "==> dummy service (mingw x86_64, static)"
DS_BUILD="$BUILD_DIR/dummy-service-build"
rm -rf "$DS_BUILD"
meson setup "$DS_BUILD" "$SRC_DIR/dummy-service" \
    --cross-file "$SRC_DIR/dummy-service/meson/x86_64-w64-mingw32.ini" \
    --buildtype=release --strip \
    -Dc_args=-mcrtdll=msvcrt-os -Dc_link_args="-static -mcrtdll=msvcrt-os" >/dev/null
meson compile -C "$DS_BUILD" GalaxyCommunication >/dev/null
mkdir -p "$(dirname "$EXE_OUT")"
x86_64-w64-mingw32-strip -o "$EXE_OUT" "$DS_BUILD/GalaxyCommunication.exe"
echo "    installed $EXE_OUT"

echo "==> results"
ls -la "$JNI_OUT" "$EXE_OUT"
file "$JNI_OUT" "$EXE_OUT"
"$NDK_BIN/llvm-readelf" -d "$JNI_OUT" | grep NEEDED || true
x86_64-w64-mingw32-objdump -p "$EXE_OUT" | grep "DLL Name" || true
