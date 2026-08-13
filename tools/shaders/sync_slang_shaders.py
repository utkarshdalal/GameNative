#!/usr/bin/env python3
"""Regenerate app/src/main/assets/retroarch/catalog.json from libretro/slang-shaders.

The APK ships NO shader files ("nenhum shader deve vir instalado" — the catalog is
metadata only). Presets are downloaded on demand by the app (single tarball, filtered
extraction of the dependency-closure union).

For every `.slangp` in the repo this script resolves the FULL dependency closure:
  - `shaderN = "<path>"`          -> .slang pass files
  - `#include "<path>"` in .slang -> .h/.inc headers, recursively (headers include headers)
  - `textures = "A;B"` + `A = "..png"` -> LUT images
  - `#reference "<path>"`         -> other presets, recursively (cycle-safe)
Paths are resolved relative to the referring file and must stay inside the repo root.
Broken presets are reported explicitly (never silently dropped).

Output schema (kotlinx-serialization friendly):
{
  "source": {"repo": "...", "ref": "...", "commit": "...", "generated": "ISO"},
  "families": [{"name": "crt", "count": 123}],
  "files": ["<union of every file any preset needs>"],
  "presets": [{"path": "crt/x.slangp", "family": "crt", "subfolder": null,
                "passes": 4, "bytes": 12345,
                "deps": ["crt/x.slangp", "crt/x.slang", "crt/include.h", ...]}]
}
Usage: python3 tools/shaders/sync_slang_shaders.py [--ref master] [--out PATH] [--fresh]
"""

import argparse
import json
import os
import re
import sys
import tarfile
import tempfile
import urllib.request
from datetime import datetime, timezone

REPO = "libretro/slang-shaders"
TARBALL_URL = "https://codeload.github.com/{repo}/tar.gz/refs/heads/{ref}"
COMMIT_URL = "https://api.github.com/repos/{repo}/commits/{ref}"
DEFAULT_OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "app", "src", "main", "assets", "retroarch", "catalog.json",
)
CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache")

# Top-level repo dirs that are not user-facing presets.
SKIP_DIRS = {"test", "spec", ".gitlab-ci.yml"}

KEY_VALUE = re.compile(r'^\s*([A-Za-z0-9_]+)\s*=\s*(?:"([^"]*)"|([^\s]+))\s*$')
REFERENCE = re.compile(r'^\s*#reference\s+"([^"]+)"')
INCLUDE = re.compile(r'^\s*#include\s+"([^"]+)"')
SHADER_KEY = re.compile(r'^shader(\d+)$')
# Dedicated capture for the textures list: upstream files sometimes carry MALFORMED
# lines that KEY_VALUE cannot represent (unterminated quotes, whitespace after
# separators, inline comments). The raw remainder of the line is parsed separately
# with quote/comment tolerance — a token that still contains whitespace is WARNED
# instead of being silently dropped (2026-08-12: technicolor.slangp regression).
TEXTURES_LINE = re.compile(r'^\s*textures\s*=\s*(.*)$')


def fetch(url: str, dest: str) -> None:
    print(f"fetching {url}", file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "gamenative-shader-sync"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as fh:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            fh.write(chunk)


def fetch_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "gamenative-shader-sync"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def fetch_commit(ref: str) -> str:
    try:
        data = fetch_json(COMMIT_URL.format(repo=REPO, ref=ref))
        return data.get("sha", "unknown")
    except Exception as exc:  # noqa: BLE001
        print(f"warning: could not fetch commit sha: {exc}", file=sys.stderr)
        return "unknown"


def ensure_extracted(ref: str, fresh: bool) -> str:
    """Download (if needed) and extract the tarball; return the extracted root dir."""
    os.makedirs(CACHE_DIR, exist_ok=True)
    tgz = os.path.join(CACHE_DIR, f"slang-shaders-{ref}.tar.gz")
    if fresh or not os.path.isfile(tgz):
        fetch(TARBALL_URL.format(repo=REPO, ref=ref), tgz)
    root = os.path.join(CACHE_DIR, f"extracted-{ref}")
    if fresh or not os.path.isdir(root):
        if os.path.isdir(root):
            import shutil
            shutil.rmtree(root)
        os.makedirs(root)
        print(f"extracting {tgz}", file=sys.stderr)
        with tarfile.open(tgz, "r:gz") as tar:
            tar.extractall(root, filter="data")
        # The tarball root is <name>-<ref>/; hoist it so the tree is the repo root.
        children = [c for c in os.listdir(root) if not c.startswith(".")]
        if len(children) == 1 and os.path.isdir(os.path.join(root, children[0])):
            inner = os.path.join(root, children[0])
            hoisted = root + "-hoisted"
            if os.path.isdir(hoisted):
                import shutil
                shutil.rmtree(hoisted)
            os.rename(inner, hoisted)
            for name in os.listdir(hoisted):
                os.replace(os.path.join(hoisted, name), os.path.join(root, name))
            os.rmdir(hoisted)
    return root


def relpath(root: str, path: str) -> str:
    return os.path.relpath(path, root).replace(os.sep, "/")


def norm_join(base_rel: str, target: str) -> str | None:
    """Resolve target relative to base_rel; None if it escapes the repo root.

    `..` operates on the COMBINED path (referring file's dir + target segments), not on
    the target alone — presets legitimately walk up across folders (e.g. Mega_Bezel
    presets reference ../../../shaders/... inside the repo).
    """
    combined = base_rel.split("/")[:-1] + [
        s for s in target.replace("\\", "/").split("/") if s not in ("", ".")
    ]
    result: list[str] = []
    for seg in combined:
        if seg == "..":
            if not result:
                return None
            result.pop()
        else:
            result.append(seg)
    return "/".join(result)


class Resolver:
    def __init__(self, root: str):
        self.root = root
        self.warnings: list[str] = []
        self._include_cache: dict[str, set[str]] = {}

    def file(self, rel: str) -> str:
        return os.path.join(self.root, *rel.split("/"))

    def exists(self, rel: str) -> bool:
        return rel and os.path.isfile(self.file(rel))

    def read(self, rel: str) -> str:
        with open(self.file(rel), "r", encoding="utf-8", errors="ignore") as fh:
            return fh.read()

    def scan_preset(self, rel: str, visited: set[str], out_deps: set[str]) -> int:
        """Return pass count; add every dependency to out_deps. Cycle-safe.

        Two passes over the preset text: collect key=value pairs and #reference targets
        first (RetroArch allows `textures = "a;b"` to be declared after the per-texture
        path lines), then interpret them.
        """
        if rel in visited:
            return 0
        visited.add(rel)
        if not self.exists(rel):
            self.warnings.append(f"missing preset referenced: {rel}")
            return 0
        text = self.read(rel)
        entries: list[tuple[str, str]] = []
        references: list[str] = []
        textures_raw: list[str] = []
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            m = REFERENCE.match(line)
            if m:
                references.append(m.group(1))
                continue
            if line.startswith("#"):
                continue
            m = TEXTURES_LINE.match(line)
            if m:
                # Raw capture: tolerant to malformed texture lists that KEY_VALUE
                # cannot represent (see TEXTURES_LINE above). Accumulated in case a
                # preset declares the list more than once (union semantics).
                textures_raw.append(m.group(1))
                continue
            m = KEY_VALUE.match(line)
            if m and (m.group(2) or m.group(3)):
                entries.append((m.group(1), m.group(2) if m.group(2) is not None else m.group(3)))

        passes = 0
        shader_targets: list[str] = []
        texture_names: set[str] = set()
        texture_targets: list[tuple[str, str]] = []
        for raw_textures in textures_raw:
            # Parse the raw textures list with quote/comment tolerance. A token that
            # still contains whitespace cannot be a valid texture key (KEY_VALUE only
            # matches [A-Za-z0-9_]+) — warn instead of dropping it silently.
            raw_value = raw_textures.replace('"', "").split("#", 1)[0].rstrip(";")
            for token in raw_value.split(";"):
                token = token.strip()
                if not token:
                    continue
                if any(ch.isspace() for ch in token):
                    self.warnings.append(f"{rel}: texture name contains whitespace: {token!r}")
                    continue
                texture_names.add(token)
        for key, value in entries:
            if SHADER_KEY.match(key):
                passes += 1
                shader_targets.append(value)
            elif key == "textures":
                # Fallback for well-formed values parsed by KEY_VALUE (the raw capture
                # above handles every `textures` line, including malformed ones).
                for token in value.replace('"', "").split(";"):
                    token = token.strip()
                    if token:
                        texture_names.add(token)
            elif key in texture_names:
                texture_targets.append((key, value))

        for target in references:
            resolved = norm_join(rel, target)
            if resolved is None:
                self.warnings.append(f"{rel}: #reference escapes root: {target}")
                continue
            if resolved.endswith(".slangp"):
                # The referenced preset file itself is a dependency: librashader opens
                # #reference targets from disk, so it must be downloaded too.
                out_deps.add(resolved)
                passes += self.scan_preset(resolved, visited, out_deps)
            elif self.exists(resolved):
                # Config/shader reference (e.g. Mega_Bezel .params): keep as dependency.
                out_deps.add(resolved)
                out_deps.update(self.include_closure(resolved))
            else:
                self.warnings.append(f"{rel}: missing #reference file: {target}")

        for value in shader_targets:
            resolved = norm_join(rel, value)
            if resolved is None:
                self.warnings.append(f"{rel}: shader escapes root: {value}")
                continue
            if self.exists(resolved):
                out_deps.add(resolved)
                out_deps.update(self.include_closure(resolved))
            else:
                self.warnings.append(f"{rel}: missing shader file: {value}")

        for key, value in texture_targets:
            resolved = norm_join(rel, value)
            if resolved is None:
                self.warnings.append(f"{rel}: texture {key} escapes root: {value}")
                continue
            if self.exists(resolved):
                out_deps.add(resolved)
            else:
                self.warnings.append(f"{rel}: missing texture file: {value}")
        return passes

    def include_closure(self, rel: str) -> set[str]:
        if rel in self._include_cache:
            return self._include_cache[rel]
        found: set[str] = set()
        stack = [rel]
        while stack:
            cur = stack.pop()
            if cur in found or not self.exists(cur):
                continue
            found.add(cur)
            for raw in self.read(cur).splitlines():
                m = INCLUDE.match(raw.strip())
                if not m:
                    continue
                target = norm_join(cur, m.group(1))
                if target is None:
                    self.warnings.append(f"{cur}: #include escapes root: {m.group(1)}")
                    continue
                if self.exists(target):
                    stack.append(target)
                else:
                    self.warnings.append(f"{cur}: missing include: {m.group(1)}")
        self._include_cache[rel] = found
        return found


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ref", default="master")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--fresh", action="store_true", help="re-download + re-extract")
    ap.add_argument("--limit", type=int, default=0, help="dev: only process first N presets")
    args = ap.parse_args()

    commit = fetch_commit(args.ref)
    root = ensure_extracted(args.ref, args.fresh)
    resolver = Resolver(root)

    preset_paths = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [
            d for d in dirnames
            if d not in SKIP_DIRS and os.path.relpath(os.path.join(dirpath, d), root) not in SKIP_DIRS
        ]
        for fn in filenames:
            if fn.endswith(".slangp"):
                rel = relpath(root, os.path.join(dirpath, fn))
                if rel.split("/")[0] in SKIP_DIRS:
                    continue
                preset_paths.append(rel)
    preset_paths.sort()
    if args.limit:
        preset_paths = preset_paths[: args.limit]

    presets = []
    pack_files: set[str] = set()
    broken_count = 0
    for rel in preset_paths:
        before = len(resolver.warnings)
        deps: set[str] = set()
        passes = resolver.scan_preset(rel, set(), deps)
        # Any warning raised while resolving this preset (or its #reference closure)
        # marks the preset as broken upstream — reported explicitly, never dropped.
        broken = len(resolver.warnings) > before
        if broken:
            broken_count += 1
        size = 0
        for dep in sorted(deps):
            pack_files.add(dep)
            if os.path.isfile(resolver.file(dep)):
                size += os.path.getsize(resolver.file(dep))
        segs = rel.split("/")
        family = segs[0] if len(segs) > 1 else "root"
        subfolder = segs[1] if len(segs) > 2 else None
        # The preset's own file is part of its closure (the app must download it too).
        deps.add(rel)
        presets.append({
            "path": rel,
            "family": family,
            "subfolder": subfolder,
            "passes": passes,
            "bytes": size,
            "deps": sorted(deps),
            "broken": broken,
        })

    family_counts: dict[str, int] = {}
    for p in presets:
        family_counts[p["family"]] = family_counts.get(p["family"], 0) + 1
    families = [{"name": k, "count": v} for k, v in sorted(family_counts.items())]

    pack_bytes = sum(os.path.getsize(resolver.file(f)) for f in pack_files)

    data = {
        "source": {
            "repo": REPO,
            "ref": args.ref,
            "commit": commit,
            "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "packBytes": pack_bytes,
        },
        "families": families,
        "files": sorted(pack_files),
        "presets": presets,
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=1)
        fh.write("\n")

    print(f"catalog: {len(presets)} presets, {len(families)} families, "
          f"{len(pack_files)} pack files, {broken_count} broken presets")
    print(f"pack bytes: {pack_bytes:,}")
    if resolver.warnings:
        print(f"warnings ({len(resolver.warnings)}), first 20:", file=sys.stderr)
        for w in resolver.warnings[:20]:
            print("  - " + w, file=sys.stderr)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
