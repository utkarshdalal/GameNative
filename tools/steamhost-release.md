# Releasing the Real Steam client package

The package contains the Valve client tree and three GameNative executables:

- `eastub.exe`, built from `GameNative/eahost` (`eastub.c`).
- `steam.exe`, built as `steamhost32.exe` from `GameNative/steamhost` (`steamhost.c`).
- `steamhost64.exe`, built from the same Steam host source.

Keep those repositories beside the GameNative checkout, or pass `--eahost` and
`--steamhost` to the packaging script. The native repositories track their built
executables. Run `python3 test_env.py` and `./build.sh` in each native repository,
then commit and push the sources, tests, and executables before packaging.

Download the current published client archive from `https://downloads.gamenative.app/`
and use it as the base. Choose a new, unused date/version for the output:

```sh
python3 tools/package-steamhost.py \
  --base /path/to/steamhost-OLD.tzst \
  --output /path/to/steamhost-YYYYMMDD.tzst
```

The script preserves the base tree and replaces exactly the three executables.
It rejects missing or duplicate executable entries, existing output files, and
uncommitted native sources/binaries. Its `.tzst.json` manifest records the base
archive hash, output hash, native commit IDs, and source/executable hashes.

Upload the archive and its manifest to the `gamenative-assets` R2 bucket using
the configured Cloudflare account, with their filenames as the object keys.
Verify the downloaded bytes at both the primary URL and the fallback URL used
by `SteamService.fetchFileWithFallback` against the manifest.

Update `REAL_STEAM_CLIENT_ARCHIVE` in `PluviaMain.kt` to the published filename.
`preLaunchApp` downloads a missing version into the app's files directory;
`extractSteamFiles` in `XServerScreen.kt` extracts it into the active Wine prefix
before launch, preserving Steam's config and user data. Merely putting an
archive into APK assets does not install it into a prefix.

Build GameNative, run the EA unit tests, and verify a device launch through that
normal download/extraction path. Compare the installed prefix's three executable
hashes with the published manifest. Commit the version update together with the
Android changes it requires.

For local development against an existing APK, this separate tool installs an
archive into both its expected cache filename and a particular game's prefix:

```sh
python3 tools/install-steamhost.py --serial DEVICE \
  --archive /path/to/steamhost-YYYYMMDD.tzst \
  --cache-name steamhost-VERSION-EXPECTED-BY-APK.tzst \
  --steam-app-id 1262560
```

Stop GameNative and Wine first. The installer refuses a running app, backs up an
existing cached archive, and verifies the cache and prefix hashes. Release
verification should use the normal downloader, not this development shortcut.
