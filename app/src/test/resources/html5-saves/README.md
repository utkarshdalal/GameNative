# html5-saves — PC-side save fixtures

Source test data for save-sync regression coverage. Pruned to the subtrees the harness
actually opens (`SaveFixtureHarness.kt`); sibling Chromium User Data dirs (Cache, GPUCache,
Code Cache, ShaderCache, Crashpad, segmentation_platform, etc.) were dropped pre-PR because
no test reads them.

## Contents

```
Local/
  SolCesto/User Data/Default/
    Local Storage/leveldb/       — Chromium localStorage (C3 misc)
    IndexedDB/
      chrome-extension_anopiimlkmdoenonenclohfilpeenfmj_0.indexeddb.leveldb/   — save payload
      chrome-extension_anopiimlkmdoenonenclohfilpeenfmj_0.indexeddb.blob/      — blob sidecar
  lookOutside/User Data/Default/
    Local Storage/leveldb/       — save payload (RMMV localStorage fallback)
    IndexedDB/
      chrome-extension_iljnakbknbffnbhpfodiohbfdnjjabfp_0.indexeddb.leveldb/   — misc
save/
  config.rpgsave                 — TERMINA RMMV filesystem save (strategy B)
  file1.rpgsave                  — TERMINA RMMV filesystem save (strategy B)
  global.rpgsave                 — TERMINA RMMV filesystem save (strategy B)
```

If a future test needs Chromium User Data evidence beyond Local Storage / IndexedDB,
re-extract from a real install — don't put the cruft back into the repo by default.

Hylics is **out of scope** for v1.

## Shapes observed

- **Shape A (per-title root):** `<name>/User Data/Default/` — SolCesto, Look Outside.
- **Shape B (flat Chromium root):** `%LOCALAPPDATA%/User Data/Default/` — TERMINA (NW.js manifest missing `user-data-dir` override). Only Chromium cruft; TERMINA saves live in the install dir via RMMV filesystem mode.
- **RMMV filesystem shape:** `<install>/www/save/*.rpgsave` — TERMINA. Base64+LZString-compressed JSON, byte-for-byte identical to the value the WebView puts in Chromium localStorage under keys `RPG Config` / `RPG File<n>` / `RPG Global`.

## PII policy — READ BEFORE `git add`

These fixtures are Chromium LevelDB blobs + RMMV save-strings. They can contain:
- Windows account username if the game writes file paths into save state.
- Save-game text (character names, player input, dialogue flags, etc.).
- Chromium telemetry strings with machine-identifying fields.

**Before committing any fixture file:**

1. Open every non-binary file (`.rpgsave`, `Local State`, `Preferences`, `README` inside `Default/`, `*.log`, `*.ldb` via `strings`) in a text viewer and scan for:
   - Real personal names / usernames.
   - Real email addresses.
   - Machine hostnames.
   - Personal save content you'd rather not publish under the repo's license.
2. For binary LevelDB files (`*.ldb`, `*.log` under `leveldb/` dirs): `strings <file> | head -200` and scan output.
3. If ANY file is uncertain → **do not `git add` it**. Keep it dev-local.

## Dev-local stash fallback

`SaveFixtureHarness` checks env var `GAMENATIVE_HTML5_SAVE_FIXTURE_ROOT` before
the classpath resource. Point it at any absolute directory whose layout matches
the one above. Example:

```sh
export GAMENATIVE_HTML5_SAVE_FIXTURE_ROOT=/Users/<you>/gamenative-fixtures/html5-saves
./gradlew :app:testDebugUnitTest --tests '*LevelDbParseProbeTest*'
```

The env-var path can be either the `html5-saves/` dir itself OR a parent that
contains it.

## Commit procedure (only after PII review passes)

```sh
# stage specific files, NEVER `git add -A` in this dir
git add app/src/test/resources/html5-saves/README.md
git add app/src/test/resources/html5-saves/Local/SolCesto/...   # each file individually
git commit -m "test(06): add reviewed save fixtures for solcesto"
```

If some fixtures pass PII review and others don't: commit the passing ones,
leave the others in the dev-local stash path.
