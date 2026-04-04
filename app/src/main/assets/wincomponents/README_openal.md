# OpenAL Soft — Audio DLL Override

The `openal.tzst` archive contains OpenAL Soft v1.25.1 DLLs that provide
OpenAL audio support for games like Mirror's Edge, some Unreal Engine 3
titles, and GOG releases that rely on OpenAL.

## How it works

OpenAL is **not** part of the Win Components system (to avoid breaking
config imports). Instead, the user adds the standard `WINEDLLOVERRIDES`
environment variable via the container's Environment tab.

### Common presets (available in the dropdown)

| WINEDLLOVERRIDES value                    | Effect                                                     |
|-------------------------------------------|------------------------------------------------------------|
| `openal32=native,builtin`                 | Extracts DLLs, Wine prefers native openal32                |
| `soft_oal=native`                         | Extracts DLLs, Wine uses native soft_oal                   |
| `openal32=native,builtin;soft_oal=native` | Extracts DLLs, both overrides at once                      |
| *(not set)*                               | No override — Wine uses its own builtin (default)          |

Users can also type any custom `WINEDLLOVERRIDES` value in the create dialog.

At boot, the app:
1. Reads `WINEDLLOVERRIDES` from the container's env vars
2. If the value mentions `openal32` or `soft_oal`, extracts `openal.tzst` into `drive_c/windows/`
3. Passes `WINEDLLOVERRIDES` directly to Wine — no translation needed

### Recommended setting

Use `openal32=native,builtin` for most games. This places `openal32.dll` (the standard
OpenAL system name) and `soft_oal.dll` (the redistributable name) in both
`system32/` and `syswow64/`, and tells Wine to prefer the native DLL.

## Archive contents

```
./system32/openal32.dll    ← 64-bit OpenAL Soft (as system openal32)
./system32/soft_oal.dll    ← 64-bit OpenAL Soft (original redistributable name)
./syswow64/openal32.dll    ← 32-bit OpenAL Soft (as system openal32)
./syswow64/soft_oal.dll    ← 32-bit OpenAL Soft (original redistributable name)
```

Both names coexist: most games load `openal32.dll` via the standard API,
but some titles (GOG releases, older UE3 games) load `soft_oal.dll` directly.

## How to rebuild openal.tzst

1. Download OpenAL Soft from https://openal-soft.org/ (latest release)
   - You need the **bin** zip with both Win32 and Win64 `soft_oal.dll`

2. Create a staging directory:
   ```
   system32/openal32.dll    (Win64 soft_oal.dll copied+renamed)
   system32/soft_oal.dll    (Win64 soft_oal.dll original name)
   syswow64/openal32.dll    (Win32 soft_oal.dll copied+renamed)
   syswow64/soft_oal.dll    (Win32 soft_oal.dll original name)
   ```

3. Create the archive:
   ```bash
   cd staging_dir
   tar -cf openal.tar ./system32 ./syswow64
   zstd openal.tar -o openal.tzst
   ```

4. Place `openal.tzst` in `app/src/main/assets/wincomponents/openal.tzst`

## Note about the pre-install step

The existing `OpenALStep` pre-install step searches for game-bundled OpenAL
installers (oalinst.exe). That step still runs regardless of this override.
This provides a system-level fallback for games that don't bundle their own.
