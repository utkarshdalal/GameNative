$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $repository "app\build\generated\xrPayload\modernXr"
$destination = Join-Path $payload "opencomposite_x64.dll"
$uri = "https://opencomposite.znix.xyz/builds/download_build?artefact_id=5r2yCwgFn_ozJ44c&build_id=49918237&commit=43e551a4506880ab1a71b8b9fec2fd7fbb27372f"
$expected = "2f56d45323f252a2ea7c3047c806f9aca3cab36c3f9f71d1dad05a5008b7731a"
New-Item -ItemType Directory -Force -Path $payload | Out-Null
$temporary = "$destination.download"
Invoke-WebRequest -Uri $uri -OutFile $temporary
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "OpenComposite checksum mismatch: $actual" }
$bytes = [System.IO.File]::ReadAllBytes($temporary)
$offset = [BitConverter]::ToInt32($bytes, 0x3c)
if ([BitConverter]::ToUInt16($bytes, $offset + 4) -ne 0x8664) { throw "OpenComposite payload is not x64" }
Move-Item -Force -LiteralPath $temporary -Destination $destination
