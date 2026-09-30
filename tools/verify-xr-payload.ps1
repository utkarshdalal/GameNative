$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $repository "app\src\modernXr\assets"
$runtime64 = Join-Path $payload "gamenative_openxr_runtime64.dll"
$runtime32 = Join-Path $payload "gamenative_openxr_runtime32.dll"
$bridge = Join-Path $payload "gamenative_xr_unixbridge.dll"
$bridge32 = Join-Path $payload "gamenative_xr_unixbridge32.dll"
$unixlib = Join-Path $payload "gamenative_xr_unixbridge.so"
$openComposite = Join-Path $payload "opencomposite_x64.dll"

foreach ($file in @($runtime64, $runtime32, $bridge, $bridge32, $unixlib, $openComposite)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing XR payload file: $file" }
}

function Get-PeMachine([string]$Path) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 256) { throw "Invalid PE image: $Path" }
    if ($bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) { throw "Invalid PE image: $Path" }
    $offset = [BitConverter]::ToInt32($bytes, 0x3c)
    if ($offset -lt 0x40 -or $offset + 6 -gt $bytes.Length) { throw "Invalid PE header: $Path" }
    if ([BitConverter]::ToUInt32($bytes, $offset) -ne 0x00004550) { throw "Invalid PE header: $Path" }
    return [BitConverter]::ToUInt16($bytes, $offset + 4)
}

if ((Get-PeMachine $runtime64) -ne 0x8664) { throw "Windows OpenXR runtime is not x64" }
if ((Get-PeMachine $runtime32) -ne 0x014c) { throw "Windows OpenXR runtime is not x86" }
if ((Get-PeMachine $bridge32) -ne 0x014c) { throw "Wine OpenXR bridge companion is not x86" }
if ((Get-PeMachine $openComposite) -ne 0x8664) { throw "OpenComposite adapter is not x64" }
& (Join-Path $PSScriptRoot "verify-arm64x-wine-pair.ps1") -CompanionPath $bridge -UnixlibPath $unixlib | Out-Host
$entries = foreach ($file in @($runtime64, $runtime32, $bridge, $bridge32, $unixlib, $openComposite)) {
    "$(Split-Path -Leaf $file) $((Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant())"
}
Set-Content -LiteralPath (Join-Path $payload "payload.version") -Value ((@("schema 4") + $entries) -join "`n")
