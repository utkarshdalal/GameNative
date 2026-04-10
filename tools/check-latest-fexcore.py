#!/usr/bin/env python3
"""
check-latest-fexcore.py
Queries the StevenMXZ/Winlator-Contents FEXCore directory and prints the
latest .wcp filename (by YYMM version number).

Usage:
    python3 tools/check-latest-fexcore.py [--token <github_token>]

A token is optional but recommended to avoid the 60 req/hr anonymous rate limit.
It can also be supplied via the GITHUB_TOKEN environment variable.
"""

import argparse
import json
import os
import re
import subprocess
import sys

API_URL = "https://api.github.com/repos/StevenMXZ/Winlator-Contents/contents/FEXCore"


def fetch_listing(token: str | None) -> list[dict]:
    cmd = [
        "curl", "-sf",
        "-H", "Accept: application/vnd.github.v3+json",
    ]
    if token:
        cmd += ["-H", f"Authorization: Bearer {token}"]
    cmd.append(API_URL)

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"curl error: {result.stderr.strip()}", file=sys.stderr)
        sys.exit(1)
    return json.loads(result.stdout)


def pick_latest(entries: list[dict]) -> tuple[str | None, tuple[int, int]]:
    best_name = None
    best_ver: tuple[int, int] = (-1, -1)
    for entry in entries:
        if entry.get("type") != "file":
            continue
        name = entry["name"]
        if not name.endswith(".wcp"):
            continue
        m = re.match(r"^(\d{4})(?:\.(\d+))?\.wcp$", name)
        if m:
            ver = (int(m.group(1)), int(m.group(2) or 0))
            if ver > best_ver:
                best_ver = ver
                best_name = name
    return best_name, best_ver


def main() -> None:
    parser = argparse.ArgumentParser(description="Find the latest FEXCore .wcp release.")
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"), help="GitHub token (or set GITHUB_TOKEN)")
    parser.add_argument(
        "--gha-output",
        metavar="FILE",
        default=os.environ.get("GITHUB_OUTPUT"),
        help="Append key=value pairs to this file (GitHub Actions $GITHUB_OUTPUT). "
             "Automatically set when GITHUB_OUTPUT env var is present.",
    )
    args = parser.parse_args()

    if not args.gha_output:
        # Human-readable mode
        print(f"Querying {API_URL} ...")

    entries = fetch_listing(args.token)

    all_wcp = [e["name"] for e in entries if e.get("type") == "file" and e["name"].endswith(".wcp")]

    latest, ver = pick_latest(entries)
    if not latest:
        print("ERROR: Could not determine latest versioned .wcp file.", file=sys.stderr)
        sys.exit(1)

    version = latest[:4]  # first four digits = YYMM
    tzst_path = f"app/src/main/assets/fexcore/fexcore-{version}.tzst"
    download_url = f"https://raw.githubusercontent.com/StevenMXZ/Winlator-Contents/main/FEXCore/{latest}"
    already_exists = os.path.isfile(tzst_path)

    if args.gha_output:
        # GitHub Actions mode: write outputs, print minimal log to stdout
        with open(args.gha_output, "a", encoding="utf-8") as f:
            f.write(f"LATEST_FILE={latest}\n")
            f.write(f"VERSION={version}\n")
            f.write(f"TZST_PATH={tzst_path}\n")
            f.write(f"ALREADY_EXISTS={'true' if already_exists else 'false'}\n")
        if already_exists:
            print(f"fexcore-{version}.tzst already present — nothing to do.")
        else:
            print(f"New release found: {latest} → {tzst_path}")
    else:
        # Human-readable mode
        print(f"\nAll .wcp files found ({len(all_wcp)}):")
        for name in sorted(all_wcp):
            print(f"  {name}")
        print(f"\nLatest : {latest}  (parsed version {ver[0]}.{ver[1]})")
        print(f"VERSION: {version}")
        print(f"Output : {tzst_path}")
        print(f"Already exists: {already_exists}")
        print(f"Download URL: {download_url}")


if __name__ == "__main__":
    main()
