#!/usr/bin/env python3
"""Replace launcher binaries in a Steam client archive and record their hashes.

Usage: python3 tools/package-steamhost.py --base steamhost-OLD.tzst \
    --output steamhost-YYYYMMDD.tzst
Build and commit ../steamhost first. Requires zstd on PATH.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_revision(repo, files):
    """Record the committed sources that produced the launcher executables."""
    revision = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    subprocess.run(["git", "-C", str(repo), "diff", "--exit-code", "HEAD", "--", *files],
                   stdout=subprocess.DEVNULL, check=True)
    return {"commit": revision, "files": {name: sha256(repo / name) for name in files}}


def main():
    workspace = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--steamhost", type=Path, default=workspace / "steamhost")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; choose a new package version")
    prefix = "home/xuser/.wine/drive_c/Program Files (x86)/Steam/"
    sources = {
        "steam.exe": args.steamhost / "steamhost32.exe",
        "steamhost64.exe": args.steamhost / "steamhost64.exe",
    }
    payloads = {prefix + name: source.read_bytes() for name, source in sources.items()}
    for name, data in payloads.items():
        if not data.startswith(b"MZ"):
            parser.error(f"{name} is not a Windows executable")
    manifest = {
        "archive": args.output.name,
        "base_sha256": sha256(args.base),
        "binaries": {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()},
        "sources": {
            "steamhost": source_revision(args.steamhost, ["steamhost.c", "build.sh", "steamhost32.exe", "steamhost64.exe"]),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as temporary:
        output = Path(temporary) / "client.tzst"
        seen = set()
        with subprocess.Popen(["zstd", "-dc", str(args.base)], stdout=subprocess.PIPE) as reader, \
             subprocess.Popen(["zstd", "-q", "-10", "-o", str(output)], stdin=subprocess.PIPE) as writer:
            with tarfile.open(fileobj=reader.stdout, mode="r|") as original, \
                 tarfile.open(fileobj=writer.stdin, mode="w|") as updated:
                for entry in original:
                    name = entry.name.removeprefix("./")
                    # Older combined archives included the EA helper; it is released separately.
                    if name == prefix + "eastub.exe":
                        continue
                    if name in payloads:
                        if name in seen:
                            raise ValueError(f"duplicate launcher entry: {name}")
                        seen.add(name)
                        replacement = tarfile.TarInfo(entry.name)
                        replacement.mode = 0o755
                        replacement.size = len(payloads[name])
                        updated.addfile(replacement, io.BytesIO(payloads[name]))
                    else:
                        updated.addfile(entry, original.extractfile(entry) if entry.isfile() else None)
            writer.stdin.close()
            if reader.wait() != 0 or writer.wait() != 0:
                raise RuntimeError("zstd failed")
        if seen != set(payloads):
            raise ValueError(f"base archive is missing launchers: {set(payloads) - seen}")
        manifest["sha256"] = sha256(output)
        output.rename(args.output)
    args.output.with_suffix(args.output.suffix + ".json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Created {args.output}; both Steam launcher binaries replaced. SHA-256: {manifest['sha256']}")


if __name__ == "__main__":
    main()
