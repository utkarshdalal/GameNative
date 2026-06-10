# build-evshim.ps1
# Rebuilds app/src/main/cpp/evshim/evshim.c into libevshim.so using the Android
# NDK clang toolchain, then strips it and copies it into jniLibs.
#
# SDL functions are resolved at runtime via dlsym(), so only the minimal SDL type
# stubs in evshim/sdl2_stub are needed to compile (no real SDL2 headers required).
# The 16 KB page-size link options match the rest of the upstream native libs.
#
# Run from the project root after editing evshim.c, then build the app normally.

$ErrorActionPreference = "Stop"

# Any installed NDK with 16 KB page-size support works; this is the one used to
# produce the committed .so.
$NDK_VERSION = "26.1.10909125"
$API         = 29   # matches the modern flavor's minSdk

if     ($env:ANDROID_SDK_ROOT) { $SDK_ROOT = $env:ANDROID_SDK_ROOT }
elseif ($env:ANDROID_HOME)     { $SDK_ROOT = $env:ANDROID_HOME }
else                           { $SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk" }

$NDK_ROOT = "$SDK_ROOT\ndk\$NDK_VERSION"
$CLANG    = "$NDK_ROOT\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android$API-clang.cmd"
$STRIP    = "$NDK_ROOT\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"

$SRC      = "app\src\main\cpp\evshim\evshim.c"
$INCLUDES = "app\src\main\cpp\evshim\sdl2_stub"
$OUT      = "app\src\main\jniLibs\arm64-v8a\libevshim.so"

if (-not (Test-Path $CLANG)) {
    Write-Error "Clang not found at: $CLANG`nInstall NDK $NDK_VERSION (or edit `$NDK_VERSION/`$API)."
}

Write-Host "Compiling evshim.c with NDK $NDK_VERSION (android-$API) ..."

$clangArgs = @(
    "-shared", "-fPIC", "-O3", "-fvisibility=hidden", "-Wall", "-Wextra",
    "-I", $INCLUDES,
    "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
    "-Wl,--as-needed", "-ldl", "-llog",
    "-o", $OUT,
    $SRC
)

& $CLANG @clangArgs
if ($LASTEXITCODE -ne 0) { Write-Error "Compilation failed (exit $LASTEXITCODE)" }

& $STRIP --strip-unneeded $OUT

Write-Host "OK  ->  $OUT"
