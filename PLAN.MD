# GameNative Online — Plan

> **Fork goal:** Make online PC multiplayer on Android a first-class choice — not a hidden toggle, not a Discord guess, not “maybe it works”.

**Upstream:** [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative)  
**This fork’s job:** Online-first UX, honest compatibility, and install-time choice: **Online build** vs **Offline build**.

---

## Why this fork exists

GameNative already runs a huge number of PC games on Android. Online multiplayer exists upstream (Bionic / Headless Steam), but for most users it still feels like this:

1. Install a game  
2. Hope  
3. Ask Discord  
4. Dig into container settings  
5. Still unsure if multiplayer actually works  

**That gap is the product.**

People do not want another Winlator-style settings maze.  
They want:

- “Does this game do online?”  
- “Install me the online version.”  
- “If online is broken, tell me why.”

This fork exists to turn experimental Steam online into something **visible, choosable, and trustworthy**.

---

## One-line pitch

**GameNative Online = GameNative, but you pick Online or Offline at install — and the app tells you the truth about multiplayer.**

---

## What makes people care

### 1. Install-time Online / Offline choice (hero feature)

If a game supports online multiplayer (Steam tags / store flags / our database), the install flow offers:

| Option | What it does | Who it’s for |
|---|---|---|
| **Offline (Recommended)** | Fast path, known configs, lower overhead | Solo players, battery, reliability |
| **Online** | Enables Bionic/Headless Steam + online-oriented config | Friends, lobbies, public servers |

UI sketch (install sheet):

```text
The Matriarch
Online multiplayer detected

○ Offline install
  Faster boot · lower RAM · solo / local

● Online install
  Steam online client · lobbies / servers
  Status: Experimental · may fail on some devices

[ Install ]
```

Rules:

- Show the choice **only if the game has online capability** (or community-confirmed online).  
- If online is unsupported / anti-cheat blocked → show a clear badge, do **not** fake an Online install.  
- Remember the choice per game; allow switch later in game settings (“Rebuild for Online / Offline”).  
- Default suggestion: **Offline** when online status is unknown/flaky; **Online** when community status is `online_ok`.

### 2. Honest Online badges (trust engine)

Every game card and detail page shows one of:

| Badge | Meaning |
|---|---|
| `OFFLINE ONLY` | Boots, no real multiplayer path |
| `ONLINE OK` | Confirmed multiplayer sessions |
| `ONLINE FLAKY` | Sometimes works; show caveats |
| `ONLINE BLOCKED` | Anti-cheat / unsupported Steam path |
| `UNTESTED ONLINE` | Has online tags, no solid reports yet |

This is the growth loop: badges → installs → reports → better badges.

### 3. Online diagnostics (turns failure into shareable signal)

When Online install fails to connect, show a short reason instead of a black box:

- Steam auth  
- Ticket / DRM  
- DNS / certificates  
- Lobby / matchmaking  
- Anti-cheat blocked  

One-tap “Copy debug report” for Discord/GitHub.  
This alone makes the fork feel 10× more serious than “toggle and pray”.

### 4. Curated Online library tab

A library filter/tab:

**Online Ready** → games with `ONLINE OK` on your device class.

This is the screenshot people share. This is why the fork spreads.

---

## Positioning vs upstream GameNative

| | Upstream GameNative | This fork |
|---|---|---|
| Focus | Best general PC-gaming client on Android | Best **online multiplayer** experience on that stack |
| Online | Powerful but experimental / easy to miss | Front-and-center at install |
| Compatibility | “Game runs” | “Game runs” **and** “Online works / doesn’t” |
| Promise | Broad library | Honest multiplayer status + one-tap Online install |

We do **not** claim Steam Deck parity.  
We claim: **clearer online, easier online, more honest online.**

---

## Target users (who will star / install / share)

1. **Friend-group mobile players** — “Can we play Matriarch / AoE / co-op on phones?”  
2. **Android handheld owners** (Odin, Retroid, GameSir, etc.) who want lobbies without a PC  
3. **Discord power users** tired of explaining container Steam toggles  
4. **Creators** who need a simple story: “Pick Online install → play”  

If the story needs 12 settings screens, we failed.

---

## Product principles

1. **Choice over magic** — Online/Offline is explicit.  
2. **Honesty over hype** — never mark anti-cheat games as Online OK.  
3. **Defaults that protect users** — Offline when unsure.  
4. **Reports build the product** — every session can improve badges.  
5. **Stay merge-friendly** — prefer layered features on upstream, avoid total rewrites.

---

## Scope of v1 (must ship to feel different)

### Must have
- [x] Detect games with online multiplayer capability  
- [x] Install sheet: **Online** vs **Offline** when applicable  
- [x] Online install auto-applies Steam online client mode + sensible Proton/config  
- [x] Per-game Online badge in library + details  
- [ ] Post-session prompt: “Did online work?” → feeds local/community status  
- [ ] Basic failure reasons in Online mode  

### Nice for v1.1
- [ ] “Online Ready” library tab  
- [ ] Rebuild game as Online/Offline without full reinstall when possible  
- [ ] Public web/list of Online OK titles for the fork  
- [ ] Invite/join UX polish on top of upstream overlay work  

### Explicit non-goals (for now)
- Bypassing anti-cheat  
- Promising online for every Steam Deck-working game  
- Competing with upstream on Play Store packaging / every storefront edge case  
- Rewriting Wine/Proton from scratch  

---

## Technical plan (high level)

### A. Online capability detection
Sources (priority order):

1. Fork database / community reports (`online_ok`, `flaky`, `blocked`)  
2. Steam store flags / tags (`Multiplayer`, `Online Co-op`, `Online PvP`)  
3. Upstream known configs (if they encode Steam client type)  
4. Fallback: `UNTESTED ONLINE` when tags say online but no reports  

### B. Install profiles
Two install profiles per eligible game:

**Offline profile**
- Current best known config  
- Online Steam client disabled / minimal  
- Optimize for boot time and stability  

**Online profile**
- Enable Bionic or Headless Steam (policy matrix by Proton/game)  
- Online-oriented env / certificates / hostname fixes where needed  
- Store `install_profile=online|offline` on the game record  

### C. UI touchpoints
1. Install confirmation dialog / bottom sheet ✅  
2. Game details page (badge + “Switch to Online/Offline”) ✅  
3. Library filters ✅  
4. First-run tip: what Online install means  
5. Automatic control setup (on-screen buttons at launch) ✅

### D. Telemetry / reports (privacy-safe)
Opt-in or align with upstream analytics policy:

- game id  
- profile used (`online`/`offline`)  
- result: boot / online connect / fail reason category  
- device class / GPU family (no personal data)  

### E. Compatibility matrix (public)
Maintain a simple living list in-repo or site:

`game | online status | proton | steam client | notes | last verified`

This becomes marketing, SEO, and contributor bait.

---

## Roadmap

### Phase 0 — Foundation (week 1) ✅
- [x] Fork sync process with upstream (`docs/UPSTREAM_SYNC.md`, `upstream` remote)
- [x] Branding: app name **GameNative Online** + About / source links in Settings → Info
- [x] Internal model: `OnlineCapability`, `OnlineStatus`, `InstallProfile`, `OnlineGameInfo`
- [x] PLAN + public README pitch + seed matrix (`docs/online/MATRIX.md`)
- Branch: all work on `develop`

### Phase 1 — Install choice (weeks 2–3) ✅ (core)
- [x] Detect online-capable games (matrix + store tags)
- [x] Install UI: Online vs Offline in Steam GameManagerDialog
- [x] Wire Online profile → Headless Steam on container create/install
- [x] Persist profile (`InstallProfileStore` + container extra)
- [x] Online status badge on game details
- [x] Post-session prompt / fail reasons (Phase 2)

### Phase 2 — Trust layer (weeks 3–5) ✅
- [x] Badges on game details + Online Ready tab
- [x] “Did online work?” prompt after Online-profile sessions (≥2 min)
- [x] Fail reason mapping (`OnlineFailReason` + copy debug report)
- [x] Seed matrix expanded (~35 titles in `online_matrix.json`)
- [x] Local report persistence (`OnlineFeedbackStore`)

### Phase 3 — Growth loop (weeks 5–8) ✅
- [x] Online Ready tab (library)
- [x] Public online compatibility page (`docs/online/COMPATIBILITY.md`)
- [x] Shareable “Online OK on my device” cards (per-game + Settings summary)
- [x] Expanded matrix (~47 titles); regenerate via `tools/generate_online_compatibility.py`

### Phase 4 — Harden (ongoing) ✅ (first pass)
- [x] Online profile policy: Headless Steam + prefer Proton 10 (no force-down from 11+)
- [x] Reassert Online flags after gamefixes on launch + preflight warnings
- [x] EOS/TLS-friendly env (`HOSTNAME`, `SteamOfflineMode=0`, `OPENSSL_ia32cap`); SSL certs via Bionic launcher
- [x] Local failure reports demote `ONLINE OK` → `FLAKY`; tags never invent `ONLINE OK`
- [x] Blocked-install warning + force Offline profile
- [x] Tighten matrix (CS/TF2 demoted to flaky until fork-verified)
- [x] Weekly sync helper `tools/sync-upstream.ps1` + `docs/UPSTREAM_SYNC.md`

---

## Success metrics

We are winning when:

1. A new user can install an online game and understand the Online/Offline choice in under 10 seconds  
2. At least **N** popular multiplayer games are marked `ONLINE OK` with reproduceable steps  
3. Support questions shift from “where is the Steam toggle?” to “this title is Blocked/Flaky — got it”  
4. The fork gets stars/installs because of **Online install**, not because it is “yet another APK”  

Suggested public targets for first release wave:

- 20 games verified Online OK  
- Install Online/Offline shipping in stable APK  
- Compatibility list published  

---

## Marketing angles (short)

- “Finally: Online or Offline at install.”  
- “Stop guessing Steam multiplayer on Android.”  
- “Badges that tell the truth: Online OK / Flaky / Blocked.”  
- “Built on GameNative. Obsessed with multiplayer.”  

Release naming idea:

- `gamenative-online-v0.1.0` — Install profiles + badges  
- `gamenative-online-v0.1.4` — Online Ready filter + store category detection  
- `gamenative-online-v0.1.5` — Auto on-screen controls at install (KBM/FPS/RTS/Gamepad + Steam Input)  
- `v0.2.0` — diagnostics + Online Ready tab  
- `v0.3.0` — public online matrix + top-game fixes  

---

## Contributor map (how others can join fast)

| Role | High-impact tasks |
|---|---|
| Android / Kotlin | Install sheet, badges, profile persistence |
| Steam / Wine | Online profile wiring, client selection policy |
| Community | Verify Online OK / Flaky / Blocked reports |
| Docs | Per-game online notes, FAQ, matrix |
| Design | Simple install choice UI, badge system |

Good first issues:

- Add online tags parser  
- Add badge component  
- Add 10 seed reports to matrix  
- Write fail-reason strings  

---

## Risks & honesty

| Risk | Mitigation |
|---|---|
| Upstream ships similar UX | Stay friendly; differentiate with matrix depth + diagnostics; contribute back when possible |
| Users expect all online games to work | Hard badges + Blocked state; no fake Online install |
| Online profile increases instability | Default Offline; warn on Experimental |
| Anti-cheat titles create drama | Explicit non-support policy in UI and README |
| Fork drifts and dies | Weekly upstream merge cadence |

---

## First build checklist

1. Clone fork and boot a debug APK  
2. Implement `InstallProfile` + detection stub (tags → show choice)  
3. Hardcode Online profile → enable Headless/Bionic path for selected test games  
4. Ship badges: `UNTESTED ONLINE` / `OFFLINE ONLY`  
5. Test with 3 titles: one known online-upstream, one blocked, one untested (`The Matriarch`)  
6. Publish APK + short demo clip of the install choice  
7. Open issues labeled `online-ok`, `install-ui`, `matrix`  

---

## North star

When someone asks:

> “Can I play this with friends on GameNative?”

The answer is no longer a thread.

It’s a badge, an install choice, and a result:

**Online install → Online OK.**  
or  
**Online Blocked — won’t waste your time.**

That clarity is what makes this fork worth existing — and worth starring.
