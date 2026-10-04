$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $repository "app\src\modernXr\assets"
$adapters = @(
    @{ Name = "opencomposite_x64.dll"; Release = "v2"; Sha256 = "55dc09c465ab2bf2787b47fec74cb9787b05aa19e1951df207ffc9dd3926af2f"; Machine = 0x8664 },
    @{ Name = "opencomposite_x86.dll"; Release = "v5"; Sha256 = "5cba39e5cd3cc2f20b3dae576ffaa84c56a750cde622e797421e01a0e680edf2"; Machine = 0x14c }
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
