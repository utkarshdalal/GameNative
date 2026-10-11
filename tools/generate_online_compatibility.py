#!/usr/bin/env python3
"""Expand online_matrix.json (optional extras) and regenerate docs/online/COMPATIBILITY.md."""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "app/src/main/assets/online_matrix.json"
OUT = ROOT / "docs/online/COMPATIBILITY.md"

EXTRA = [
    {"appId": "8930", "store": "steam", "status": "online_flaky", "notes": "Sid Meier's Civilization V"},
    {"appId": "289070", "store": "steam", "status": "online_flaky", "notes": "Civilization VI"},
    {"appId": "252490", "store": "steam", "status": "online_blocked", "notes": "Rust — anti-cheat"},
    {"appId": "304930", "store": "steam", "status": "online_flaky", "notes": "Unturned"},
    {"appId": "578080", "store": "steam", "status": "online_blocked", "notes": "PUBG — anti-cheat"},
    {"appId": "381210", "store": "steam", "status": "online_blocked", "notes": "Dead by Daylight — EAC"},
    {"appId": "218620", "store": "steam", "status": "online_flaky", "notes": "PAYDAY 2"},
    {"appId": "227300", "store": "steam", "status": "online_flaky", "notes": "Euro Truck Simulator 2 Convoy"},
    {"appId": "526870", "store": "steam", "status": "untested_online", "notes": "Satisfactory"},
    {"appId": "1203220", "store": "steam", "status": "untested_online", "notes": "NARAKA: BLADEPOINT"},
    {"appId": "1422450", "store": "steam", "status": "untested_online", "notes": "Deadlock"},
    {"appId": "1938090", "store": "steam", "status": "online_blocked", "notes": "Call of Duty — anti-cheat"},
]

ORDER = [
    "online_ok",
    "online_flaky",
    "untested_online",
    "offline_only",
    "online_blocked",
]
LABELS = {
    "online_ok": "ONLINE OK",
    "online_flaky": "ONLINE FLAKY",
    "untested_online": "UNTESTED ONLINE",
    "offline_only": "OFFLINE ONLY",
    "online_blocked": "ONLINE BLOCKED",
}


def main() -> None:
    data = json.loads(MATRIX.read_text(encoding="utf-8"))
    existing = {e["appId"] for e in data["entries"]}
    for entry in EXTRA:
        if entry["appId"] not in existing:
            data["entries"].append(entry)
            existing.add(entry["appId"])
    MATRIX.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    by: dict[str, list] = defaultdict(list)
    for entry in data["entries"]:
        by[entry["status"]].append(entry)

    lines = [
        "# GameNative Online — Compatibility",
        "",
        "> Public online multiplayer status for this fork. Source: [`online_matrix.json`](../../app/src/main/assets/online_matrix.json).",
        "",
        "**Repo:** [Vanyc-00X/GameNative-Online](https://github.com/Vanyc-00X/GameNative-Online) · **Branch:** `develop`",
        "",
        "Install with **Online** or **Offline** when a game supports multiplayer. Badges are honest: we do not mark anti-cheat titles as Online OK.",
        "",
        "| Badge | Meaning |",
        "|---|---|",
        "| ONLINE OK | Confirmed / upstream-reported multiplayer path |",
        "| ONLINE FLAKY | Sometimes works; expect issues |",
        "| UNTESTED ONLINE | Has online tags; needs reports |",
        "| OFFLINE ONLY | Treat as single-player / no online path here |",
        "| ONLINE BLOCKED | Anti-cheat or unsupported — do not expect online |",
        "",
        f"_Entries: {len(data['entries'])}_",
        "",
    ]

    for status in ORDER:
        items = sorted(by.get(status, []), key=lambda x: (x.get("notes") or x["appId"]).lower())
        if not items:
            continue
        lines.append(f"## {LABELS[status]} ({len(items)})")
        lines.append("")
        lines.append("| App ID | Notes |")
        lines.append("|---|---|")
        for entry in items:
            note = (entry.get("notes") or "").replace("|", "\\|")
            app_id = entry["appId"]
            lines.append(f"| [{app_id}](https://store.steampowered.com/app/{app_id}/) | {note} |")
        lines.append("")

    lines.extend(
        [
            "## How to contribute",
            "",
            "1. Install a game with the **Online** profile in GameNative Online.",
            "2. Play for a couple of minutes and answer **Did online multiplayer work?**",
            "3. Use **Share online status** on the game page, or open a PR updating `online_matrix.json`.",
            "",
            "Regenerate this page:",
            "",
            "```bash",
            "python tools/generate_online_compatibility.py",
            "```",
            "",
            "Generated for the fork — not an official Valve / upstream GameNative list.",
            "",
        ]
    )
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"entries={len(data['entries'])} -> {OUT}")


if __name__ == "__main__":
    main()
