# snappy-java (fork, submodule)

- **Source:** `upstream/` → https://github.com/jeremybernstein/snappy-java, branch `gamenative`
- **Based on:** xerial/snappy-java tag `v1.1.10.8`; bundles google/snappy `1.1.10` as a nested submodule
- **License:** Apache 2.0, unchanged

Needs `git submodule update --init --recursive` — recursive, the C++ is a submodule of the fork.

## Why a fork

The `libsnappyjava.so` in the maven jar is 4KB-aligned; Play requires 16KB for 64-bit libs.
Upstream cross-compiles via a Docker toolchain Gradle can't drive, so the fork adds an NDK/CMake
build. `1.1.10.8` is the latest release — there is nothing newer to upgrade into.

## Fork patches

1. `android/CMakeLists.txt` — NDK build with `-Wl,-z,max-page-size=16384` + `common-page-size`,
   matching GameNative's other native targets. Verified `0x4000` on both ABIs, 15 JNI symbols.
2. armeabi-v7a fix — snappy's NEON probe passes on 32-bit ARM but `V128_Shuffle` uses AArch64-only
   intrinsics, so upstream doesn't compile there. Separate commit; unrelated to alignment.

`BitShuffleNative` isn't built — needs separate bitshuffle sources, nothing here calls it.

## Layout

Java from this module (submodule `src/main/java`), native from the app's `externalNativeBuild`.
One pinned commit for both.

`SnappyBundleActivator` excluded (OSGi). Bundled natives excluded from the shipped artifact and
republished as a `desktop-natives` jar for host tests only.
