# Releasing the Real Steam client package

`GameNative/steamhost` builds `steamhost32.exe` (packaged as `steam.exe`) and
`steamhost64.exe`. EA helpers are released independently by the downstream EA branch.

Run `python3 test_env.py` and `./build.sh` in the native repository, then commit
and push its sources, tests, and executables. Download the current public client
archive from `https://downloads.gamenative.app/` and choose a new version:

```sh
python3 tools/package-steamhost.py --base /path/to/steamhost-OLD.tzst \
  --output /path/to/steamhost-YYYYMMDD.tzst --steamhost /path/to/steamhost
```

The script replaces the two Steam executables and removes `eastub.exe` if the
base is an older combined archive. Other entries are preserved. It rejects
uncommitted native sources/binaries and writes a `.tzst.json` manifest containing
source commit IDs and source, executable, base, and output hashes.

Upload the archive and manifest to the `gamenative-assets` R2 bucket, using their
filenames as object keys. Verify both download URLs from
`SteamService.fetchFileWithFallback` against the output hash. Update
`REAL_STEAM_CLIENT_ARCHIVE` in `PluviaMain.kt` to the published filename.

`preLaunchApp` downloads the new version into the app files directory;
`extractSteamFiles` installs it into the active Wine prefix before launch,
preserving Steam config and user data. Verify that normal path on a device.

For development against an existing APK, stop GameNative and Wine, then use:

```sh
python3 tools/install-steamhost.py --serial DEVICE \
  --archive /path/to/steamhost-YYYYMMDD.tzst \
  --cache-name steamhost-VERSION-EXPECTED-BY-APK.tzst --steam-app-id 1262560
```

This installer updates and verifies the cached archive and both prefix executables.
It does not install an EA helper. Release verification uses the normal downloader.
