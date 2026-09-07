$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
# Patches and builds live in https://github.com/GameNative/opencomposite.
$uri = "https://github.com/GameNative/opencomposite/releases/download/v1/opencomposite_x64.dll"
$expected = "b669d08a6fdb9461dd239c5f5de702001c8fbfff235f5560a0117485daa6b6c9"
$patchMarker = "Ignoring VRApplication_Background: no shared OpenVR server is available"
if ($expected -notmatch '^[0-9a-f]{64}$') { throw "Pin the v1 release SHA-256 before staging OpenComposite" }
function Test-OpenComposite([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() -ne $expected) { return $false }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) { throw "Invalid OpenComposite PE image" }
    $offset = [BitConverter]::ToInt32($bytes, 0x3c)
    if ($offset -lt 64 -or $offset -gt $bytes.Length - 6) { throw "Invalid OpenComposite PE header" }
    if ([BitConverter]::ToUInt32($bytes, $offset) -ne 0x00004550 -or [BitConverter]::ToUInt16($bytes, $offset + 4) -ne 0x8664) { throw "OpenComposite payload is not x64" }
    if (-not [System.Text.Encoding]::ASCII.GetString($bytes).Contains($patchMarker)) { throw "OpenComposite payload does not contain the GameNative app-type fix" }
    return $true
}
$destination = Join-Path $repository "app\src\modernXr\assets\opencomposite_x64.dll"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
if (-not (Test-OpenComposite $destination)) {
    $temporary = "$destination.download"
    try {
        Invoke-WebRequest -Uri $uri -OutFile $temporary
        if (-not (Test-OpenComposite $temporary)) { throw "OpenComposite checksum mismatch" }
        Move-Item -Force -LiteralPath $temporary -Destination $destination
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
$legacy = Join-Path $repository "app\src\legacyXr\assets\opencomposite_x64.dll"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $legacy) | Out-Null
if (-not (Test-OpenComposite $legacy)) { Copy-Item -Force -LiteralPath $destination -Destination $legacy }
