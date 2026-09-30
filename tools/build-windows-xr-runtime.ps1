param(
    [string]$Configuration = "Release"
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$source = Join-Path $repository "app\src\main\windows\openxr_runtime"
$build = Join-Path $repository "app\build\windows-xr-runtime"
$output = Join-Path $repository "app\src\modernXr\assets"
$ndk = Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk\29.0.14206865"
$clang = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"
$dlltool = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-dlltool.exe"
if (!(Test-Path $clang) -or !(Test-Path $dlltool)) { throw "Android NDK 29 LLVM tools are required" }
New-Item -ItemType Directory -Force -Path $build, $output | Out-Null
cmake -S $source -B $build -G "Visual Studio 17 2022" -A x64
if ($LASTEXITCODE -ne 0) { throw "OpenXR SDK preparation failed" }
$include = Join-Path $build "_deps\openxr_sdk-build\include"
if (!(Test-Path (Join-Path $include "openxr\openxr.h"))) { throw "OpenXR SDK headers are unavailable" }

function Invoke-Tool {
    param([scriptblock]$Command, [string]$Name)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

$imports = @{}
foreach ($architecture in @("x64", "x86")) {
    $machine = if ($architecture -eq "x64") { "i386:x86-64" } else { "i386" }
    foreach ($library in @("ws2_32", "kernel32", "ntdll", "dxgi")) {
        $archive = Join-Path $build "lib${library}_${architecture}.a"
        $definition = Join-Path $source "${library}_${architecture}.def"
        if ($architecture -eq "x86") {
            Invoke-Tool { & $dlltool -m $machine -k -d $definition -l $archive } "$library $architecture import library"
        } else {
            Invoke-Tool { & $dlltool -m $machine -d $definition -l $archive } "$library $architecture import library"
        }
        $imports["$library-$architecture"] = $archive
    }
}

$runtimeSource = Join-Path $source "gamenative_openxr_runtime.c"
$runtime64 = Join-Path $build "gamenative_openxr_runtime64.dll"
$runtime32 = Join-Path $build "gamenative_openxr_runtime32.dll"
Invoke-Tool { & $clang --target=x86_64-w64-windows-gnu -shared -nostdlib "-Wl,-e,DllMain" -I $include -o $runtime64 $runtimeSource (Join-Path $source "gamenative_openxr_runtime_x64.def") $imports["ws2_32-x64"] $imports["kernel32-x64"] $imports["ntdll-x64"] $imports["dxgi-x64"] } "x64 OpenXR runtime"
Invoke-Tool { & $clang --target=i686-w64-windows-gnu -shared -nostdlib "-Wl,-e,DllMain" -I $include -o $runtime32 $runtimeSource (Join-Path $source "gamenative_openxr_runtime_x86.def") $imports["ws2_32-x86"] $imports["kernel32-x86"] $imports["ntdll-x86"] $imports["dxgi-x86"] } "x86 OpenXR runtime"

function Assert-Machine {
    param([string]$Path, [int]$Expected)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $offset = [BitConverter]::ToInt32($bytes, 0x3c)
    $actual = [BitConverter]::ToUInt16($bytes, $offset + 4)
    if ($actual -ne $Expected) { throw "Unexpected PE machine for $Path" }
}
Assert-Machine $runtime64 0x8664
Assert-Machine $runtime32 0x014c
Copy-Item -Force -LiteralPath $runtime64 -Destination (Join-Path $output "gamenative_openxr_runtime64.dll")
Copy-Item -Force -LiteralPath $runtime32 -Destination (Join-Path $output "gamenative_openxr_runtime32.dll")
$hash64 = (Get-FileHash -Algorithm SHA256 -LiteralPath $runtime64).Hash.ToLowerInvariant()
$hash32 = (Get-FileHash -Algorithm SHA256 -LiteralPath $runtime32).Hash.ToLowerInvariant()
Set-Content -NoNewline -LiteralPath (Join-Path $output "payload.version") -Value "3 $hash64 $hash32"
