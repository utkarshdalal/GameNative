#!/usr/bin/env python3
"""Install a package-steamhost.py artifact into both the device cache and a game prefix.

The app must be stopped before running this tool. It backs up the cached archive,
replaces each file atomically, and verifies the installed executables by SHA-256.
Example: --serial DEVICE --archive /tmp/steamhost-YYYYMMDD.tzst \
    --cache-name steamhost-YYYYMMDD.tzst --steam-app-id 1262560
The cache name must match REAL_STEAM_CLIENT_ARCHIVE in the APK being tested.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--cache-name", required=True)
    parser.add_argument("--steam-app-id", required=True, type=int)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if args.steam_app_id <= 0 or not re.fullmatch(r"steamhost-[\w-]+\.tzst", args.cache_name):
        parser.error("invalid app ID or cache filename")
    manifest = json.loads(args.archive.with_suffix(args.archive.suffix + ".json").read_text())
    digest = hashlib.sha256(args.archive.read_bytes()).hexdigest()
    if digest != manifest["sha256"]:
        parser.error("archive does not match its manifest")
    adb = [args.adb, "-s", args.serial]

    def shell(command):
        return subprocess.check_output(adb + ["shell", command], text=True).strip()

    def run_as(command):
        return shell(shlex.join(["run-as", "app.gamenative", "sh", "-c", command]))

    # Refuse to replace files while the app or one of its Wine children is active.
    uid = run_as("id -u")
    processes = shell("ps -A -o UID,NAME")
    if any(line.split() and line.split()[0] == uid and
           (line.split()[-1] == "app.gamenative" or "wine" in line.split()[-1].lower())
           for line in processes.splitlines()[1:]):
        parser.error("stop GameNative and its Wine processes before installing")

    prefix = f"files/imagefs_shared/home/xuser-STEAM_{args.steam_app_id}/.wine/drive_c/Program Files (x86)/Steam"
    run_as("test -d " + shlex.quote(prefix))
    archive_prefix = "home/xuser/.wine/drive_c/Program Files (x86)/Steam/"
    expected = {name.removeprefix(archive_prefix): value for name, value in manifest["binaries"].items()}
    if set(expected) != {"steam.exe", "steamhost64.exe"}:
        parser.error("manifest must describe the two Steam launcher binaries")
    remote = f"/data/local/tmp/gn-steamhost-{digest[:12]}"
    shell("mkdir -p " + shlex.quote(remote))
    try:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with subprocess.Popen(["zstd", "-dc", str(args.archive)], stdout=subprocess.PIPE) as reader:
                with tarfile.open(fileobj=reader.stdout, mode="r|") as archive:
                    for entry in archive:
                        name = entry.name.removeprefix("./")
                        if name.startswith(archive_prefix) and name[len(archive_prefix):] in expected:
                            (root / name[len(archive_prefix):]).write_bytes(archive.extractfile(entry).read())
                if reader.wait() != 0:
                    raise RuntimeError("cannot decompress archive")
            for name, checksum in expected.items():
                if hashlib.sha256((root / name).read_bytes()).hexdigest() != checksum:
                    raise ValueError(f"payload mismatch: {name}")
                subprocess.run(adb + ["push", str(root / name), remote + "/" + name], check=True)
            subprocess.run(adb + ["push", str(args.archive), remote + "/client.tzst"], check=True)
            cached = "files/" + args.cache_name
            backup = cached + ".before-" + digest[:12]
            run_as(f"if test -f {shlex.quote(cached)} && ! test -e {shlex.quote(backup)}; then cp {shlex.quote(cached)} {shlex.quote(backup)}; fi")
            for name, target in [("client.tzst", cached)] + [(name, prefix + "/" + name) for name in expected]:
                new = target + ".new"
                run_as(f"cp {shlex.quote(remote + '/' + name)} {shlex.quote(new)} && chmod 700 {shlex.quote(new)} && mv {shlex.quote(new)} {shlex.quote(target)}")
            for name, checksum in expected.items():
                actual = run_as("sha256sum " + shlex.quote(prefix + "/" + name)).split()[0]
                if actual != checksum:
                    raise ValueError(f"installed file mismatch: {name}")
            if run_as("sha256sum " + shlex.quote(cached)).split()[0] != digest:
                raise ValueError("cached archive mismatch")
    finally:
        shell("rm -rf " + shlex.quote(remote))
    print("Installed and verified both Steam prefix binaries and the cached archive. Relaunch the game to use them.")


if __name__ == "__main__":
    main()
