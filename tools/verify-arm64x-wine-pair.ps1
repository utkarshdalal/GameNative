param(
    [Parameter(Mandatory = $true)]
    [string]$CompanionPath,
    [Parameter(Mandatory = $true)]
    [string]$UnixlibPath
)

$ErrorActionPreference = "Stop"

function Read-U16([byte[]]$Bytes, [int64]$Offset) {
    if ($Offset -lt 0 -or $Offset + 2 -gt $Bytes.Length) { throw "Truncated binary field" }
    [BitConverter]::ToUInt16($Bytes, [int]$Offset)
}

function Read-U32([byte[]]$Bytes, [int64]$Offset) {
    if ($Offset -lt 0 -or $Offset + 4 -gt $Bytes.Length) { throw "Truncated binary field" }
    [BitConverter]::ToUInt32($Bytes, [int]$Offset)
}

function Read-U64([byte[]]$Bytes, [int64]$Offset) {
    if ($Offset -lt 0 -or $Offset + 8 -gt $Bytes.Length) { throw "Truncated binary field" }
    [BitConverter]::ToUInt64($Bytes, [int]$Offset)
}

function Find-RvaOffset([byte[]]$Bytes, [int64]$PeOffset, [uint32]$Rva) {
    $sectionCount = Read-U16 $Bytes ($PeOffset + 6)
    $optionalSize = Read-U16 $Bytes ($PeOffset + 20)
    $sectionTable = $PeOffset + 24 + $optionalSize
    for ($index = 0; $index -lt $sectionCount; $index++) {
        $section = $sectionTable + 40 * $index
        $virtualSize = Read-U32 $Bytes ($section + 8)
        $virtualAddress = Read-U32 $Bytes ($section + 12)
        $rawSize = Read-U32 $Bytes ($section + 16)
        $rawOffset = Read-U32 $Bytes ($section + 20)
        $mappedSize = [Math]::Max($virtualSize, $rawSize)
        if ($Rva -ge $virtualAddress -and $Rva -lt $virtualAddress + $mappedSize) {
            $offset = $rawOffset + ($Rva - $virtualAddress)
            if ($offset -ge $Bytes.Length) { throw "PE RVA maps outside the image" }
            return [int64]$offset
        }
    }
    throw "PE RVA is not mapped by a section"
}

function Has-Ascii([byte[]]$Bytes, [string]$Value) {
    $needle = [System.Text.Encoding]::ASCII.GetBytes($Value)
    for ($offset = 0; $offset -le $Bytes.Length - $needle.Length; $offset++) {
        $matched = $true
        for ($index = 0; $index -lt $needle.Length; $index++) {
            if ($Bytes[$offset + $index] -ne $needle[$index]) {
                $matched = $false
                break
            }
        }
        if ($matched) { return $true }
    }
    return $false
}

$companion = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $CompanionPath))
if ($companion.Length -lt 512 -or (Read-U16 $companion 0) -ne 0x5a4d) { throw "Wine XR bridge is not a valid PE image" }
$peOffset = Read-U32 $companion 0x3c
if ((Read-U32 $companion $peOffset) -ne 0x00004550) { throw "Wine XR bridge PE signature is invalid" }
$machine = Read-U16 $companion ($peOffset + 4)
if ($machine -ne 0xaa64 -and $machine -ne 0xa64e) { throw "Wine XR bridge physical machine is neither ARM64 nor ARM64X" }
$optionalOffset = $peOffset + 24
if ((Read-U16 $companion $optionalOffset) -ne 0x20b) { throw "Wine XR bridge is not PE32+" }
$sectionAlignment = Read-U32 $companion ($optionalOffset + 32)
$fileAlignment = Read-U32 $companion ($optionalOffset + 36)
if ($sectionAlignment -ne 65536 -or $fileAlignment -ne 65536) { throw "Wine XR bridge alignment is not 64 KiB" }
if ([System.Text.Encoding]::ASCII.GetString($companion, 64, 16) -ne "Wine builtin DLL") { throw "Wine XR bridge has no Wine builtin signature" }
if (-not (Has-Ascii $companion "gnWineUnixCall")) { throw "Wine XR bridge export is missing" }
$directoryCount = Read-U32 $companion ($optionalOffset + 108)
if ($directoryCount -le 10) { throw "Wine XR bridge has no load configuration directory" }
$loadConfigRva = Read-U32 $companion ($optionalOffset + 112 + 10 * 8)
$loadConfigSize = Read-U32 $companion ($optionalOffset + 116 + 10 * 8)
if ($loadConfigRva -eq 0 -or $loadConfigSize -lt 208) { throw "Wine XR bridge load configuration cannot contain CHPE metadata" }
$loadConfigOffset = Find-RvaOffset $companion $peOffset $loadConfigRva
$imageBase = Read-U64 $companion ($optionalOffset + 24)
$chpePointer = Read-U64 $companion ($loadConfigOffset + 200)
if ($chpePointer -eq 0) { throw "Wine XR bridge has no CHPE metadata pointer" }
$chpeRva = if ($chpePointer -ge $imageBase) { $chpePointer - $imageBase } else { $chpePointer }
$chpeOffset = Find-RvaOffset $companion $peOffset ([uint32]$chpeRva)
$chpeVersion = Read-U32 $companion $chpeOffset
$codeMapRva = Read-U32 $companion ($chpeOffset + 4)
$codeMapCount = Read-U32 $companion ($chpeOffset + 8)
if ($chpeVersion -eq 0 -or $codeMapRva -eq 0 -or $codeMapCount -eq 0 -or $codeMapCount -gt 1048576) { throw "Wine XR bridge CHPE metadata is incomplete" }
$codeMapOffset = Find-RvaOffset $companion $peOffset $codeMapRva
$hasArm64 = $false
$hasArm64Ec = $false
for ($index = 0; $index -lt $codeMapCount; $index++) {
    $rangeType = (Read-U32 $companion ($codeMapOffset + 8 * $index)) -band 3
    if ($rangeType -eq 0) { $hasArm64 = $true }
    if ($rangeType -eq 1) { $hasArm64Ec = $true }
}
if (-not $hasArm64 -or -not $hasArm64Ec) { throw "Wine XR bridge CHPE code map is not ARM64X hybrid" }

$unixlib = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $UnixlibPath))
if ($unixlib.Length -lt 64 -or $unixlib[0] -ne 0x7f -or $unixlib[1] -ne 0x45 -or $unixlib[2] -ne 0x4c -or
    $unixlib[3] -ne 0x46 -or $unixlib[4] -ne 2 -or $unixlib[5] -ne 1 -or (Read-U16 $unixlib 18) -ne 183) {
    throw "Wine XR unixlib is not a little-endian aarch64 ELF"
}
if (-not (Has-Ascii $unixlib "__wine_unix_call_funcs") -or -not (Has-Ascii $unixlib "__wine_unix_call_wow64_funcs")) {
    throw "Wine XR unixlib dispatch exports are missing"
}

[PSCustomObject]@{
    PhysicalMachine = ('0x{0:x4}' -f $machine)
    ChpeVersion = $chpeVersion
    ChpeCodeRanges = $codeMapCount
    SectionAlignment = $sectionAlignment
    FileAlignment = $fileAlignment
    CompanionSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $CompanionPath).Hash.ToLowerInvariant()
    UnixlibSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $UnixlibPath).Hash.ToLowerInvariant()
}
