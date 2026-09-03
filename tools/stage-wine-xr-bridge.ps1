param(
    [Parameter(Mandatory = $true)]
    [string]$CompanionPath
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repository "app\src\modernXr\assets"
$unixlib = Join-Path $output "gamenative_xr_unixbridge.so"
$companion32 = Join-Path $repository "app\src\main\windows\openxr_runtime\builtin\gamenative_xr_unixbridge32.dll"
if (-not (Test-Path -LiteralPath $companion32 -PathType Leaf)) { throw "Missing x86 Wine XR bridge companion" }
& (Join-Path $PSScriptRoot "verify-arm64x-wine-pair.ps1") -CompanionPath $CompanionPath -UnixlibPath $unixlib | Out-Host
New-Item -ItemType Directory -Force -Path $output | Out-Null
Copy-Item -Force -LiteralPath $CompanionPath -Destination (Join-Path $output "gamenative_xr_unixbridge.dll")
Copy-Item -Force -LiteralPath $companion32 -Destination (Join-Path $output "gamenative_xr_unixbridge32.dll")
