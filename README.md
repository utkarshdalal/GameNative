<div align="center">

# GameNative Online

**Fork of GameNative focused on online multiplayer — pick Online or Offline at install, and see the truth about multiplayer status.**

[![License](https://img.shields.io/badge/license-GPL%203.0-blue?style=flat-square)](LICENSE)
[![Plan](https://img.shields.io/badge/plan-PLAN.MD-0A7A3E?style=flat-square)](PLAN.MD)
[![Upstream](https://img.shields.io/badge/upstream-GameNative-111111?style=flat-square)](https://github.com/utkarshdalal/GameNative)

</div>

---

## Why this fork

[GameNative](https://github.com/utkarshdalal/GameNative) already runs PC games from Steam, Epic, GOG and Amazon on Android. Online multiplayer exists upstream (Bionic / Headless Steam), but for most people it is easy to miss and hard to trust.

**GameNative Online** makes multiplayer a first-class product:

- **Install choice** — if a game has online multiplayer, choose **Online** or **Offline** at install
- **Honest badges** — `ONLINE OK` / `FLAKY` / `BLOCKED` / `UNTESTED` instead of guessing
- **Diagnostics** — when online fails, show a clear reason category
- **Online Ready** — a library path for titles that actually multiplayer

> One-line pitch: **GameNative, but you pick Online or Offline at install — and the app tells you the truth about multiplayer.**

Full plan: [PLAN.MD](PLAN.MD) · Online matrix seed: [docs/online/MATRIX.md](docs/online/MATRIX.md) · Upstream sync: [docs/UPSTREAM_SYNC.md](docs/UPSTREAM_SYNC.md)

## Status

**Phase 0–4 first pass complete**

- Install dialog: **Online** vs **Offline** (blocked titles forced Offline)
- Headless Steam + Proton 10 preference for Online installs; reasserted after gamefixes
- Online status badges + **Online Ready** library tab
- Post-session **Did online work?** prompt; local failures demote false `ONLINE OK`
- Public list: [docs/online/COMPATIBILITY.md](docs/online/COMPATIBILITY.md)
- Shareable **Online OK on my device** cards (game page + Settings)
- Matrix seed (~47 titles) in `app/src/main/assets/online_matrix.json`
- Weekly upstream sync: `tools/sync-upstream.ps1`

**Next:** more fork-verified Online OK titles; keep weekly upstream merges.

## Releases

Tagged builds: `gamenative-online-v*` via [`.github/workflows/online-release.yml`](.github/workflows/online-release.yml) → [Releases](https://github.com/Vanyc-00X/GameNative-Online/releases).

## What you get (inherited + fork direction)

From upstream GameNative:

- Play games you own on Steam, Epic, GOG and Amazon
- Cloud saves, known configs, controllers, DLC / workshop / branches

From this fork (rolling out):

- Online vs Offline install profiles
- Per-game online status badges
- Public online compatibility matrix

## Honesty

- Online is **not** on by default in upstream; this fork makes the choice explicit.
- We do **not** claim Steam Deck parity for every online title.
- Anti-cheat titles may be marked `ONLINE BLOCKED` — we will not fake support.

## Branches

| Branch | Use |
|---|---|
| `develop` | Active development (use this) |
| `master` | Stable / sync baseline |

## Building

Same as upstream: open in Android Studio and build the `app` module.

Optional SteamGridDB key in `local.properties`:

```properties
STEAMGRIDDB_API_KEY=your_api_key_here
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Work happens on `develop`.

Please credit upstream GameNative for the core stack. This fork’s focus is online UX and compatibility clarity.

## License

[GPL 3.0](LICENSE).

Based on [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative). See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for bundled component notices.

---

**Disclaimer:** This software is meant for playing games that you legally own. Don't use it for piracy or anything else illegal. The maintainer takes no responsibility for misuse.
