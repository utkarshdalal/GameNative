param(
    [string]$Configuration = "Release"
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$sdkLine = Get-Content -LiteralPath (Join-Path $repository "local.properties") | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
if (-not $sdkLine) { throw "sdk.dir is missing from local.properties" }
$sdk = $sdkLine.Substring(8).Replace("\\", "\").Replace("\:", ":")
$ndk = Join-Path $sdk "ndk\27.3.13750724"
$cmake = Join-Path $sdk "cmake\3.22.1\bin\cmake.exe"
$ninja = Join-Path $sdk "cmake\3.22.1\bin\ninja.exe"
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE ".gradle" }
$aarCache = Join-Path $gradleHome "caches\modules-2\files-2.1\org.khronos.openxr\openxr_loader_for_android\1.1.61"
$aar = if (Test-Path -LiteralPath $aarCache) { Get-ChildItem $aarCache -Recurse -Filter "openxr_loader_for_android-1.1.61.aar" | Select-Object -First 1 }
if (-not $aar) { throw "OpenXR Android loader 1.1.61 is not in the Gradle cache ($gradleHome); run a Gradle sync first so it is downloaded" }
$work = Join-Path $repository "app\build\xr-native"
$dependency = Join-Path $work "openxr"
$build = Join-Path $work "build"
$output = Join-Path $repository "app\src\modernXr\jniLibs\arm64-v8a"
$unixBuild = Join-Path $repository "app\build\xr-unixlib"
$payloadOutput = Join-Path $repository "app\src\modernXr\assets"
New-Item -ItemType Directory -Force -Path $dependency, $build, $output | Out-Null
if (-not (Test-Path (Join-Path $dependency "prefab"))) {
    tar -xf $aar.FullName -C $dependency
    if ($LASTEXITCODE -ne 0) { throw "Could not extract the OpenXR Android loader" }
}
$openXrDirectory = Join-Path $dependency "prefab\modules\openxr_loader\libs\android.arm64-v8a\cmake\openxr"
& $cmake -S (Join-Path $repository "app\src\main\cpp\xrimmersive") -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_BUILD_TYPE=$Configuration" "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 "-DOpenXR_DIR=$openXrDirectory"
if ($LASTEXITCODE -ne 0) { throw "OpenXR native configuration failed" }
& $cmake --build $build --config $Configuration
if ($LASTEXITCODE -ne 0) { throw "OpenXR native build failed" }
Copy-Item -Force -LiteralPath (Join-Path $build "libxrimmersive.so") -Destination (Join-Path $output "libxrimmersive.so")
New-Item -ItemType Directory -Force -Path $unixBuild, $payloadOutput | Out-Null
& $cmake -S (Join-Path $repository "app\src\main\windows\openxr_runtime\unix") -B $unixBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_BUILD_TYPE=$Configuration" "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
if ($LASTEXITCODE -ne 0) { throw "Wine unixlib configuration failed" }
& $cmake --build $unixBuild --config $Configuration
if ($LASTEXITCODE -ne 0) { throw "Wine unixlib build failed" }
Copy-Item -Force -LiteralPath (Join-Path $unixBuild "gamenative_xr_unixbridge.so") -Destination (Join-Path $payloadOutput "gamenative_xr_unixbridge.so")
