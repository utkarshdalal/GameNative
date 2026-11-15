#!/usr/bin/env bash
# Builds Valve's Fossilize (https://github.com/ValveSoftware/Fossilize) for the
# bionic container used by GameNative:
#   - app/src/main/jniLibs/arm64-v8a/libfossilize_layer.so     the recording layer, installed per container
#                                                              as libVkLayer_fossilize.so by FossilizeManager
#                                                              (not libVkLayer_* in the APK, so Android's own
#                                                              loader does not scan it)
#   - app/src/main/jniLibs/arm64-v8a/libfossilize_replay.so    fossilize-replay (a PIE executable,
#                                                              run from nativeLibraryDir)
#
# Pinned upstream: commit 556da9ff7d7616704dda515756219ab1dab693ec (2026-09-24);
# override with FOSSILIZE_REF=<commit>.
# Patches in tools/fossilize-patches/*.patch are applied with `git apply`:
#   - layer/instance.cpp, layer/dispatch.cpp: take the Linux code path on bionic, so the
#     layer honours FOSSILIZE_DUMP_PATH / _READ_ONLY / the app-info filter instead of
#     getprop, and exports the manifest-named entry points
#   - layer/utils.hpp: CLI tools log to stderr as well as logcat
#   - cli/, fossilize_db.cpp: enable the crash-isolated replayer on bionic (memfd_create
#     instead of shm_open for the control block and the exported metadata) and re-exec
#     --progress through /system/bin/linker64
#
# Prerequisites:
#   - Android NDK r27d (ANDROID_NDK_HOME, else probes the default SDK locations
#     for ndk/27.3.13750724), cmake and ninja on PATH.
#   - a python3 with a working pyexpat for the SPIRV-Tools codegen (the system
#     /usr/bin/python3 on macOS; Homebrew 3.14 is broken). Override with FOSSILIZE_PYTHON.
#   - git and network access (clones Fossilize with submodules).
set -euo pipefail

FOSSILIZE_URL="${FOSSILIZE_URL:-https://github.com/ValveSoftware/Fossilize.git}"
FOSSILIZE_REF="${FOSSILIZE_REF:-556da9ff7d7616704dda515756219ab1dab693ec}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH_DIR="$REPO_ROOT/tools/fossilize-patches"
BUILD_DIR="${FOSSILIZE_BUILD_DIR:-$REPO_ROOT/build/fossilize}"
SRC_DIR="$BUILD_DIR/src"
OUT_DIR="$BUILD_DIR/android-arm64-v8a"
JNI_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    for c in "$HOME/Library/Android/sdk/ndk/27.3.13750724" "$HOME/Android/Sdk/ndk/27.3.13750724"; do
        [[ -d "$c" ]] && ANDROID_NDK_HOME="$c" && break
    done
fi
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an NDK r27d install}"
case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64" ;;
    Linux) HOST_TAG="linux-x86_64" ;;
    *) echo "unsupported host $(uname -s)" >&2; exit 1 ;;
esac
STRIP="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
PYTHON="${FOSSILIZE_PYTHON:-/usr/bin/python3}"

echo "==> Fossilize $FOSSILIZE_REF into $SRC_DIR"
mkdir -p "$BUILD_DIR"
if [[ ! -d "$SRC_DIR/.git" ]]; then
    git clone --quiet --recurse-submodules "$FOSSILIZE_URL" "$SRC_DIR"
fi
git -C "$SRC_DIR" fetch --quiet origin
git -C "$SRC_DIR" checkout --quiet --force "$FOSSILIZE_REF"
git -C "$SRC_DIR" submodule update --init --recursive --quiet
git -C "$SRC_DIR" reset --quiet --hard
for p in "$PATCH_DIR"/*.patch; do
    echo "==> applying $(basename "$p")"
    git -C "$SRC_DIR" apply "$p"
done

echo "==> cmake (aarch64-linux-android28, static libc++, 16K pages)"
rm -rf "$OUT_DIR"
cmake -S "$SRC_DIR" -B "$OUT_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DFOSSILIZE_TESTS=OFF \
    -DPython3_EXECUTABLE="$PYTHON" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL"
ninja -C "$OUT_DIR" VkLayer_fossilize fossilize-replay

echo "==> install into $JNI_DIR"
cp "$OUT_DIR/layer/libVkLayer_fossilize.so" "$JNI_DIR/libfossilize_layer.so"
"$STRIP" -o "$JNI_DIR/libfossilize_replay.so" "$OUT_DIR/cli/fossilize-replay"
ls -la "$JNI_DIR"/libfossilize_layer.so "$JNI_DIR"/libfossilize_replay.so
