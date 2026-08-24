param(
    [switch]$PrintToolchainFingerprint
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $repository "app\build\generated\xrPayload\modernXr"
$destination = Join-Path $payload "opencomposite_x64.dll"
$commit = "a27e7e6a64bdcd1eff6b7fba1ea2ea34bcf1273d"
$source = Join-Path $repository "app\build\opencomposite-source"
$build = Join-Path $repository "app\build\opencomposite-build"
$patch = Join-Path $PSScriptRoot "patches\opencomposite-gamenative-wine.patch"
$vulkanDefinition = Join-Path $PSScriptRoot "opencomposite-vulkan-x64.def"
$ndk = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk\29.0.14206865" }
$ndkIncludes = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\include"
$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
$visualStudioVersionRange = "[17.0,18.0)"

function Invoke-Checked {
    param([scriptblock]$Command, [string]$Name)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

function Get-DirectoryFingerprint {
    param([string]$Path)
    $resolvedRoot = (Resolve-Path -LiteralPath $Path).Path.TrimEnd('\') + '\'
    $manifest = Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File |
        Sort-Object FullName |
        ForEach-Object {
            $relativePath = $_.FullName.Substring($resolvedRoot.Length).Replace('\', '/')
            $fileHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            "$relativePath|$($_.Length)|$fileHash"
        }
    $manifestBytes = [Text.Encoding]::UTF8.GetBytes(($manifest -join "`n"))
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($hasher.ComputeHash($manifestBytes))).Replace('-', '')
    } finally {
        $hasher.Dispose()
    }
}

if (!(Test-Path (Join-Path $ndkIncludes "vulkan\vulkan.h"))) { throw "Android NDK Vulkan headers are required" }
if (!(Test-Path $vswhere)) { throw "Visual Studio Installer is required" }
$visualStudio = (& $vswhere -latest -version $visualStudioVersionRange -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | Select-Object -First 1)
if (!$visualStudio) { throw "Visual Studio 2022 C++ x64 build tools are required" }
$libTool = Get-ChildItem (Join-Path $visualStudio "VC\Tools\MSVC\*\bin\Hostx64\x64\lib.exe") | Sort-Object FullName -Descending | Select-Object -First 1
if (!$libTool) { throw "Visual Studio lib.exe is required" }

if ($PrintToolchainFingerprint) {
    $ndkSourceProperties = Join-Path $ndk "source.properties"
    if (!(Test-Path -LiteralPath $ndkSourceProperties -PathType Leaf)) { throw "Android NDK source.properties is required" }
    $visualStudioVersion = (& $vswhere -latest -version $visualStudioVersionRange -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationVersion | Select-Object -First 1)
    if (!$visualStudioVersion) { throw "Visual Studio version could not be resolved" }
    $toolDirectory = Split-Path -Parent $libTool.FullName
    $compilerTool = Join-Path $toolDirectory "cl.exe"
    $linkerTool = Join-Path $toolDirectory "link.exe"
    if (!(Test-Path -LiteralPath $compilerTool -PathType Leaf)) { throw "Visual Studio cl.exe is required" }
    if (!(Test-Path -LiteralPath $linkerTool -PathType Leaf)) { throw "Visual Studio link.exe is required" }
    $fingerprint = [ordered]@{
        ndkPath = (Resolve-Path -LiteralPath $ndk).Path
        ndkSourcePropertiesSha256 = (Get-FileHash -LiteralPath $ndkSourceProperties -Algorithm SHA256).Hash
        ndkVulkanHeadersSha256 = Get-DirectoryFingerprint (Join-Path $ndkIncludes "vulkan")
        ndkVideoHeadersSha256 = Get-DirectoryFingerprint (Join-Path $ndkIncludes "vk_video")
        visualStudioPath = (Resolve-Path -LiteralPath $visualStudio).Path
        visualStudioVersion = $visualStudioVersion
        compilerPath = (Resolve-Path -LiteralPath $compilerTool).Path
        compilerSha256 = (Get-FileHash -LiteralPath $compilerTool -Algorithm SHA256).Hash
        linkerSha256 = (Get-FileHash -LiteralPath $linkerTool -Algorithm SHA256).Hash
        librarianSha256 = (Get-FileHash -LiteralPath $libTool.FullName -Algorithm SHA256).Hash
    }
    $fingerprint | ConvertTo-Json -Compress
    exit 0
}

New-Item -ItemType Directory -Force -Path $payload | Out-Null
if (!(Test-Path (Join-Path $source ".git"))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $source) | Out-Null
    Invoke-Checked { git clone --no-checkout https://gitlab.com/znixian/OpenOVR.git $source } "OpenComposite clone"
}
& git -C $source cat-file -e "$commit^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    Invoke-Checked { git -C $source fetch --depth 1 origin $commit } "OpenComposite source fetch"
}
Invoke-Checked { git -C $source checkout --detach --force $commit } "OpenComposite checkout"
Invoke-Checked { git -C $source submodule update --init --recursive --depth 1 } "OpenComposite submodules"
Invoke-Checked { git -C $source apply --check $patch } "OpenComposite patch check"
Invoke-Checked { git -C $source apply $patch } "OpenComposite patch"

$vulkan = Join-Path $source "libs\vulkan"
$vulkanInclude = Join-Path $vulkan "Include\vulkan"
$videoInclude = Join-Path $vulkan "Include\vk_video"
New-Item -ItemType Directory -Force -Path $vulkanInclude, $videoInclude, (Join-Path $vulkan "Lib") | Out-Null
Copy-Item -Recurse -Force -Path (Join-Path $ndkIncludes "vulkan\*") -Destination $vulkanInclude
Copy-Item -Recurse -Force -Path (Join-Path $ndkIncludes "vk_video\*") -Destination $videoInclude
Copy-Item -Force -LiteralPath $vulkanDefinition -Destination (Join-Path $vulkan "vulkan-1.def")
Invoke-Checked { & $libTool.FullName /nologo "/def:$(Join-Path $vulkan 'vulkan-1.def')" /machine:x64 "/out:$(Join-Path $vulkan 'Lib\vulkan-1.lib')" } "Vulkan import library"

Invoke-Checked { cmake --fresh -S $source -B $build -G "Visual Studio 17 2022" -A x64 "-DCMAKE_GENERATOR_INSTANCE=$visualStudio" -DERROR_ON_WARNING=OFF "-DOC_VERSION=$commit-gamenative-scene-only" } "OpenComposite configure"
Invoke-Checked { cmake --build $build --config Release --target OCOVR --parallel } "OpenComposite build"
$built = Join-Path $build "bin\Release\vrclient_x64.dll"
if (!(Test-Path -LiteralPath $built -PathType Leaf)) { throw "OpenComposite build output is missing" }
Copy-Item -Force -LiteralPath $built -Destination $destination
$bytes = [System.IO.File]::ReadAllBytes($destination)
$offset = [BitConverter]::ToInt32($bytes, 0x3c)
if ([BitConverter]::ToUInt16($bytes, $offset + 4) -ne 0x8664) { throw "OpenComposite payload is not x64" }
