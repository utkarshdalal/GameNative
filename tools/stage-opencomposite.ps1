$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $repository "app\src\modernXr\assets"
$adapters = @(
    @{ Name = "opencomposite_x64.dll"; Release = "v10"; Sha256 = "0d395d267734bba3efa514edb836e728ac8c94ae2228c5ea31fca51a2d2819d8"; Machine = 0x8664 },
    @{ Name = "opencomposite_x86.dll"; Release = "v10"; Sha256 = "86a77b58c817ed0b14f4e7963c1364d47f86b53cc75fed0ba6d04e87d1040059"; Machine = 0x14c }
)
New-Item -ItemType Directory -Force -Path $payload | Out-Null
foreach ($adapter in $adapters) {
    $destination = Join-Path $payload $adapter.Name
    if ((Test-Path -LiteralPath $destination -PathType Leaf) -and (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant() -eq $adapter.Sha256) { continue }
    $temporary = "$destination.download"
    Invoke-WebRequest -Uri "https://github.com/GameNative/opencomposite/releases/download/$($adapter.Release)/$($adapter.Name)" -OutFile $temporary
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToLowerInvariant()
    if ($actual -ne $adapter.Sha256) { throw "$($adapter.Name) checksum mismatch: $actual" }
    $bytes = [System.IO.File]::ReadAllBytes($temporary)
    $offset = [BitConverter]::ToInt32($bytes, 0x3c)
    if ([BitConverter]::ToUInt16($bytes, $offset + 4) -ne $adapter.Machine) { throw "$($adapter.Name) has the wrong machine type" }
    Move-Item -Force -LiteralPath $temporary -Destination $destination
}
