# Upstream sync

This repository is a fork of [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative).

Remotes:

| Remote | URL |
|---|---|
| `origin` | `https://github.com/Vanyc-00X/GameNative-Online.git` |
| `upstream` | `https://github.com/utkarshdalal/GameNative.git` |

## Branch model

| Branch | Role |
|---|---|
| `master` | Stable mirror / release-ready |
| `develop` | Active fork work (default for PRs and features) |

## Weekly sync (recommended)

Helper (PowerShell, from repo root):

```powershell
powershell -ExecutionPolicy Bypass -File tools/sync-upstream.ps1
```

Manual:

```bash
git checkout develop
git fetch upstream
git merge upstream/master
# resolve conflicts if any, prefer keeping fork online models / branding
git push origin develop
```

If merge noise is high:

```bash
git checkout develop
git fetch upstream
git rebase upstream/master
git push origin develop
```

Only force-push `develop` if you rebased **and** you intend to rewrite that branch history.

## Conflict rules

When upstream and this fork touch the same area:

1. Keep fork branding (`GameNative Online`, GitHub links, about strings).
2. Keep `app.gamenative.online.*` models and future install-profile UI.
3. Prefer upstream fixes for Wine/Proton/Steam runtime bugs.
4. Re-test Online/Offline install paths after every sync.

## Do not

- Rewrite upstream history.
- Remove GPL notices or third-party attributions.
- Market this fork as the original GameNative project.
