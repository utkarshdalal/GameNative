$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $repository "app\src\modernXr\assets"
$adapters = @(
    @{ Name = "opencomposite_x64.dll"; Release = "v9"; Sha256 = "d9d542bcb11acb607c5a4714e77e9c4833b7760a61d9706379214acd2f2375c9"; Machine = 0x8664 },
    @{ Name = "opencomposite_x86.dll"; Release = "v9"; Sha256 = "2bdf7b4e6a425af149a21f578b951f1dfe0f9e3fe2a6e8c0ff3604a25aee9911"; Machine = 0x14c }
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
