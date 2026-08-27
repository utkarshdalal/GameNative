param(
    [string]$Destination,
    [string]$Branch = "gamenative-native-openvr"
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $repository
$source = if ($Destination) { [IO.Path]::GetFullPath($Destination) } else { Join-Path $workspace "OpenComposite-gamenative" }
$upstream = "https://gitlab.com/znixian/OpenOVR.git"
$commit = "a27e7e6a64bdcd1eff6b7fba1ea2ea34bcf1273d"
$compatibilityPatch = Join-Path $PSScriptRoot "patches\opencomposite-gamenative-wine.patch"
$nativeBackendPatch = Join-Path $PSScriptRoot "patches\opencomposite-gamenative-native-backend.patch"

function Invoke-Checked {
    param([scriptblock]$Command, [string]$Name)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

if (!(Test-Path (Join-Path $source ".git"))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $source) | Out-Null
    Invoke-Checked { git clone --recurse-submodules $upstream $source } "OpenComposite clone"
}

$status = (& git -c "safe.directory=$source" -C $source status --porcelain)
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect OpenComposite checkout" }
if ($status) { throw "OpenComposite checkout has local changes; refusing to switch or patch it: $source" }

$remoteNames = @(& git -c "safe.directory=$source" -C $source remote)
if ($remoteNames -contains "origin" -and !($remoteNames -contains "upstream")) {
    Invoke-Checked { git -c "safe.directory=$source" -C $source remote rename origin upstream } "OpenComposite remote rename"
}
if (!(@(& git -c "safe.directory=$source" -C $source remote) -contains "upstream")) {
    Invoke-Checked { git -c "safe.directory=$source" -C $source remote add upstream $upstream } "OpenComposite upstream remote"
}
Invoke-Checked { git -c "safe.directory=$source" -C $source remote set-url --push upstream DISABLED } "OpenComposite upstream push protection"

& git -c "safe.directory=$source" -C $source cat-file -e "$commit^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    Invoke-Checked { git -c "safe.directory=$source" -C $source fetch upstream $commit } "OpenComposite pinned revision fetch"
}

& git -c "safe.directory=$source" -C $source show-ref --verify --quiet "refs/heads/$Branch"
if ($LASTEXITCODE -eq 0) {
    Invoke-Checked { git -c "safe.directory=$source" -C $source switch $Branch } "OpenComposite branch switch"
} else {
    Invoke-Checked { git -c "safe.directory=$source" -C $source switch --create $Branch $commit } "OpenComposite branch creation"
}
Invoke-Checked { git -c "safe.directory=$source" -C $source merge-base --is-ancestor $commit HEAD } "OpenComposite pinned-base validation"
Invoke-Checked { git -c "safe.directory=$source" -C $source submodule update --init --recursive } "OpenComposite submodules"

& git -c "safe.directory=$source" -C $source apply --reverse --check $compatibilityPatch 2>$null
if ($LASTEXITCODE -ne 0) {
    Invoke-Checked { git -c "safe.directory=$source" -C $source apply --check $compatibilityPatch } "OpenComposite compatibility patch check"
    Invoke-Checked { git -c "safe.directory=$source" -C $source apply $compatibilityPatch } "OpenComposite compatibility patch"
    Write-Host "Applied the GameNative Wine compatibility patch. Review and commit it in the OpenComposite checkout."
}

& git -c "safe.directory=$source" -C $source apply --reverse --check $nativeBackendPatch 2>$null
if ($LASTEXITCODE -ne 0) {
    Invoke-Checked { git -c "safe.directory=$source" -C $source apply --check $nativeBackendPatch } "OpenComposite native backend patch check"
    Invoke-Checked { git -c "safe.directory=$source" -C $source apply $nativeBackendPatch } "OpenComposite native backend patch"
    Write-Host "Applied the GameNative direct OpenVR backend patch. Review and commit it in the OpenComposite checkout."
}

Write-Host "OpenComposite native-OpenVR checkout ready"
Write-Host "  source: $source"
Write-Host "  branch: $Branch"
Write-Host "  pinned upstream: $commit"
Write-Host "  remote: upstream (fetch-only by convention; add your fork as origin before pushing)"
