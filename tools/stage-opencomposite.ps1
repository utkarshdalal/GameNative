$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $repository "app\src\modernXr\assets"
$destination = Join-Path $payload "opencomposite_x64.dll"
$uri = "https://github.com/GameNative/opencomposite/releases/download/v2/opencomposite_x64.dll"
$expected = "55dc09c465ab2bf2787b47fec74cb9787b05aa19e1951df207ffc9dd3926af2f"
New-Item -ItemType Directory -Force -Path $payload | Out-Null
if ((Test-Path -LiteralPath $destination -PathType Leaf) -and (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant() -eq $expected) { exit 0 }
$temporary = "$destination.download"
Invoke-WebRequest -Uri $uri -OutFile $temporary
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "OpenComposite checksum mismatch: $actual" }
$bytes = [System.IO.File]::ReadAllBytes($temporary)
$offset = [BitConverter]::ToInt32($bytes, 0x3c)
if ([BitConverter]::ToUInt16($bytes, $offset + 4) -ne 0x8664) { throw "OpenComposite payload is not x64" }
Move-Item -Force -LiteralPath $temporary -Destination $destination
