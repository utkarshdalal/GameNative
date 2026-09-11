#!/usr/bin/env python3
"""Package the committed EA helper independently of the Steam client tree."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--eahost", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; choose a new package version")
    files = ["eastub.c", "build.sh", "eastub.exe"]
    subprocess.run(["git", "-C", str(args.eahost), "diff", "--exit-code", "HEAD", "--", *files],
                   stdout=subprocess.DEVNULL, check=True)
    revision = subprocess.check_output(["git", "-C", str(args.eahost), "rev-parse", "HEAD"], text=True).strip()
    data = (args.eahost / "eastub.exe").read_bytes()
    if not data.startswith(b"MZ"):
        parser.error("eastub.exe is not a Windows executable")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as temporary:
        output = Path(temporary) / "ea.tzst"
        with subprocess.Popen(["zstd", "-q", "-10", "-o", str(output)], stdin=subprocess.PIPE) as writer:
            with tarfile.open(fileobj=writer.stdin, mode="w|") as archive:
                entry = tarfile.TarInfo("eastub.exe")
                entry.mode, entry.size = 0o755, len(data)
                archive.addfile(entry, io.BytesIO(data))
            writer.stdin.close()
            if writer.wait() != 0:
                raise RuntimeError("zstd failed")
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        output.rename(args.output)
    manifest = {
        "archive": args.output.name, "sha256": digest,
        "binaries": {"eastub.exe": hashlib.sha256(data).hexdigest()},
        "sources": {"eahost": {"commit": revision,
                    "files": {name: hashlib.sha256((args.eahost / name).read_bytes()).hexdigest() for name in files}}},
    }
    args.output.with_suffix(args.output.suffix + ".json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
