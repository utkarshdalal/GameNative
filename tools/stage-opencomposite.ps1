$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $repository "app\src\modernXr\assets"
$destination = Join-Path $payload "opencomposite_x64.dll"
$uri = "https://opencomposite.znix.xyz/builds/download_build?artefact_id=JjRFMXaxas695QK-&build_id=52366409&commit=a27e7e6a64bdcd1eff6b7fba1ea2ea34bcf1273d"
$expected = "827ad85f3606a4dc4a8f5561a8ca69e4c6c1b5d2b9cd3315a461b9270b08242c"
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
