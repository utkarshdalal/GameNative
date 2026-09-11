# Releasing the EA helper

EA is downstream of the Steam branch and ships its helper separately:

- `real-steam-steamhost` owns the Steam client archive and its download version.
- `ea-lsx` owns the EA archive, LSX/authentication, and EA-specific installation.
- The Rockstar branch inherits both through merges.

Build, test, commit, and push `GameNative/eahost` before packaging:

```sh
python3 tools/package-eahost.py --eahost /path/to/eahost \
  --output /path/to/eahost-YYYYMMDD.tzst
```

The archive contains only `eastub.exe`. Its adjacent JSON manifest records the
native commit, source hashes, executable hash, and archive hash. Upload both to
`gamenative-assets`, verify the public download hashes, then update `ARCHIVE`,
`ARCHIVE_SHA256`, and `BINARY_SHA256` in `EaHelperArchive.kt`.

`preLaunchApp` downloads the EA archive only for detected EA games using Real
Steam. `EaLaunchSupport.start` installs it into the prefix after Steam's binary
cleanup and before starting LSX/Steam. Extraction validates the package and
executable hashes and replaces the helper atomically. An archive in the APK or
download cache alone is not an installed helper.

Run the EA tests and verify a real launch with both new archives initially absent
from the device cache. Verify the two Steam executables and `eastub.exe` on disk.
