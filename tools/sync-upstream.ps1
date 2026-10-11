# Weekly upstream sync helper for GameNative-Online (develop).
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File tools/sync-upstream.ps1

$ErrorActionPreference = "Stop"

function Require-CleanTree {
    $status = git status --porcelain
    if ($status) {
        Write-Error "Working tree is not clean. Commit or stash before syncing."
    }
}

Require-CleanTree

git remote get-url upstream 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    git remote add upstream https://github.com/utkarshdalal/GameNative.git
}

Write-Host "Fetching upstream..."
git fetch upstream

$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne "develop") {
    Write-Host "Checking out develop..."
    git checkout develop
}

Write-Host "Merging upstream/master into develop..."
git merge upstream/master --no-edit
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Merge conflicts detected. Keep fork online branding/models; prefer upstream for Wine/Proton/Steam runtime fixes."
    Write-Host "See docs/UPSTREAM_SYNC.md"
    exit 1
}

Write-Host "Sync complete. Review, then: git push origin develop"
