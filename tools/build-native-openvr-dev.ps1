param(
    [string]$SourceDirectory,
    [string]$BuildDirectory,
    [string]$Destination
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $repository
$source = if ($SourceDirectory) { [IO.Path]::GetFullPath($SourceDirectory) } else { Join-Path $workspace "OpenComposite-gamenative" }
$build = if ($BuildDirectory) { [IO.Path]::GetFullPath($BuildDirectory) } else { Join-Path $repository "app\build\native-openvr-dev" }
$output = if ($Destination) { [IO.Path]::GetFullPath($Destination) } else { Join-Path $build "opencomposite_x64.dll" }
$stage = Join-Path $PSScriptRoot "stage-opencomposite.ps1"

& powershell -ExecutionPolicy Bypass -File $stage -SourceDirectory $source -BuildDirectory $build -Destination $output
if ($LASTEXITCODE -ne 0) { throw "Native OpenVR developer build failed with exit code $LASTEXITCODE" }

Write-Host "Native OpenVR developer payload ready: $output"
Write-Host "This isolated output is not packaged into the APK unless explicitly staged later."
