# GameNative Online — Compatibility

> Public online multiplayer status for this fork. Source: [`online_matrix.json`](../../app/src/main/assets/online_matrix.json).

**Repo:** [Vanyc-00X/GameNative-Online](https://github.com/Vanyc-00X/GameNative-Online) · **Branch:** `develop`

Install with **Online** or **Offline** when a game supports multiplayer. Badges are honest: we do not mark anti-cheat titles as Online OK.

| Badge | Meaning |
|---|---|
| ONLINE OK | Confirmed / upstream-reported multiplayer path |
| ONLINE FLAKY | Sometimes works; expect issues |
| UNTESTED ONLINE | Has online tags; needs reports |
| OFFLINE ONLY | Treat as single-player / no online path here |
| ONLINE BLOCKED | Anti-cheat or unsupported — do not expect online |

_Entries: 47_

## ONLINE OK (2)

| App ID | Notes |
|---|---|
| [221380](https://store.steampowered.com/app/221380/) | Age of Empires II (HD) |
| [813780](https://store.steampowered.com/app/813780/) | Age of Empires II: DE — upstream headless multiplayer fixes |

## ONLINE FLAKY (23)

| App ID | Notes |
|---|---|
| [251570](https://store.steampowered.com/app/251570/) | 7 Days to Die |
| [945360](https://store.steampowered.com/app/945360/) | Among Us |
| [346110](https://store.steampowered.com/app/346110/) | ARK: Survival Evolved |
| [289070](https://store.steampowered.com/app/289070/) | Civilization VI |
| [730](https://store.steampowered.com/app/730/) | Counter-Strike family — VAC online path; not fork-verified Online OK |
| [240](https://store.steampowered.com/app/240/) | Counter-Strike: Source |
| [322330](https://store.steampowered.com/app/322330/) | Don't Starve Together |
| [239140](https://store.steampowered.com/app/239140/) | Dying Light |
| [227300](https://store.steampowered.com/app/227300/) | Euro Truck Simulator 2 Convoy |
| [4000](https://store.steampowered.com/app/4000/) | Garry's Mod |
| [271590](https://store.steampowered.com/app/271590/) | GTA V Legacy — Rockstar online not guaranteed |
| [550](https://store.steampowered.com/app/550/) | Left 4 Dead 2 |
| [218620](https://store.steampowered.com/app/218620/) | PAYDAY 2 |
| [620](https://store.steampowered.com/app/620/) | Portal 2 co-op |
| [108600](https://store.steampowered.com/app/108600/) | Project Zomboid |
| [1174180](https://store.steampowered.com/app/1174180/) | Red Dead Redemption 2 — Rockstar online flaky |
| [252950](https://store.steampowered.com/app/252950/) | Rocket League — may need EOS |
| [8930](https://store.steampowered.com/app/8930/) | Sid Meier's Civilization V |
| [440](https://store.steampowered.com/app/440/) | Team Fortress 2 — Steam multiplayer often works; treat as flaky until fork-verified |
| [105600](https://store.steampowered.com/app/105600/) | Terraria |
| [304930](https://store.steampowered.com/app/304930/) | Unturned |
| [892970](https://store.steampowered.com/app/892970/) | Valheim |
| [236390](https://store.steampowered.com/app/236390/) | War Thunder |

## UNTESTED ONLINE (9)

| App ID | Notes |
|---|---|
| [291550](https://store.steampowered.com/app/291550/) | Brawlhalla |
| [1422450](https://store.steampowered.com/app/1422450/) | Deadlock |
| [1966720](https://store.steampowered.com/app/1966720/) | Lethal Company |
| [1203220](https://store.steampowered.com/app/1203220/) | NARAKA: BLADEPOINT |
| [1623730](https://store.steampowered.com/app/1623730/) | Palworld |
| [526870](https://store.steampowered.com/app/526870/) | Satisfactory |
| [413150](https://store.steampowered.com/app/413150/) | Stardew Valley co-op |
| [1773180](https://store.steampowered.com/app/1773180/) | The Matriarch |
| [438100](https://store.steampowered.com/app/438100/) | VRChat |

## OFFLINE ONLY (4)

| App ID | Notes |
|---|---|
| [1091500](https://store.steampowered.com/app/1091500/) | Cyberpunk 2077 |
| [220](https://store.steampowered.com/app/220/) | Half-Life 2 |
| [367520](https://store.steampowered.com/app/367520/) | Hollow Knight |
| [400](https://store.steampowered.com/app/400/) | Portal |

## ONLINE BLOCKED (9)

| App ID | Notes |
|---|---|
| [1172470](https://store.steampowered.com/app/1172470/) | Apex Legends — EAC |
| [1938090](https://store.steampowered.com/app/1938090/) | Call of Duty — anti-cheat |
| [381210](https://store.steampowered.com/app/381210/) | Dead by Daylight — EAC |
| [570](https://store.steampowered.com/app/570/) | Dota 2 — VAC |
| [1245620](https://store.steampowered.com/app/1245620/) | Elden Ring — EAC online |
| [578080](https://store.steampowered.com/app/578080/) | PUBG — anti-cheat |
| [359550](https://store.steampowered.com/app/359550/) | Rainbow Six Siege — BattlEye |
| [252490](https://store.steampowered.com/app/252490/) | Rust — anti-cheat |
| [552990](https://store.steampowered.com/app/552990/) | World of Warships |

## How to contribute

1. Install a game with the **Online** profile in GameNative Online.
2. Play for a couple of minutes and answer **Did online multiplayer work?**
3. Use **Share online status** on the game page, or open a PR updating `online_matrix.json`.

Regenerate this page:

```bash
python tools/generate_online_compatibility.py
```

Generated for the fork — not an official Valve / upstream GameNative list.
