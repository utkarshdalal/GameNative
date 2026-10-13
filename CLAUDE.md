# GameNativeDuo

A fork of [GameNative](https://github.com/utkarshdalal/GameNative) (Windows PC games on Android via Wine,
Box64/FEX, DXVK) that adds **dual-screen support for Android handhelds**, starting with the **AYN Thor**,
and grows into a **devkit** others can build bottom-screen tools and per-game mods on.

## Target device: AYN Thor

- Snapdragon 8 Gen 2 (Adreno 740), Android 13. Build the `modern` flavour.
- Top screen: 6" 1920x1080 AMOLED, 120 Hz. Android's **default** display. The game runs here.
- Bottom screen: 3.92" 1240x1080 AMOLED, 60 Hz, touch. Android reports it as a **secondary
  (presentation) display**. Other dual-screen devices (AYANEO Pocket DS, Retroid Dual Screen) are reversed,
  so code says "top/bottom" through device profiles, never raw display IDs.
- Ground truth for display layout: run a game, then `adb logcat -s GNDuo` or pull
  `/sdcard/Android/data/app.gamenative.duo/files/duo/displays.json`.

## Golden rule: keep upstream merges cheap

Upstream lands ~4-5 commits/day; `XServerScreen.kt` (~6,800 lines) changes almost daily.

- New code goes in **new files** under `app/src/main/java/app/gamenative/duo/` (package `app.gamenative.duo`)
  and `app/src/main/res/values/duo_*.xml`. A separate Gradle module was rejected: our code needs
  `XServer`, renderers and `Container` from `:app`, which would be a circular dependency.
- Touch upstream files only with **one-line hooks**, marked `// GameNativeDuo`. Never add fields to
  `Container.java` or the container settings dialog; store devkit settings in our own files keyed by
  container id.
- Prefer changes that upstream would accept, and offer them upstream (see "Upstream candidates").
- Sync by merging upstream release tags into `devkit` (merge, never rebase a published branch).

### Every upstream file we modify (keep this list current)

| File | Change |
|---|---|
| `ui/screen/xserver/XServerScreen.kt` | import + one `DualScreenHost.attach(...)` line after the external-display controllers start |
| `app/build.gradle.kts` | `applicationId = "app.gamenative.duo"` |
| `AndroidManifest.xml` | 3 launcher labels -> `@string/duo_app_name` |
| `com/winlator/container/Container.java` | `AppPaths` for default drives/MEDIACONV paths; `setDrives`/`setEnvVars` rewrite old paths |
| `com/winlator/core/DXVKHelper.java`, `WineUtils.java` | `AppPaths` instead of `/data/data/app.gamenative` |
| `xenvironment/components/BionicProgramLauncherComponent.java` | `AppPaths` for gamepad mem files; sets `EVSHIM_BASE_PATH` |
| `.github/workflows/{app-release-signed,tagged-release,issues-contributors-only}.yml` | jobs guarded to run only in upstream repo |

## Why `app.gamenative.duo` and `AppPaths`

The fork needs its own applicationId to install beside official GameNative. Upstream hardcoded
`/data/data/app.gamenative/...` in several places (and community configs from GameNative's servers contain
such paths), which breaks under any other id. `com.winlator.core.AppPaths` derives paths from
`BuildConfig.APPLICATION_ID` and `AppPaths.rewrite()` fixes stored strings. It is a no-op for upstream's id,
so it is an **upstream candidate** (it also fixes their `.gold` variant). Keep the `app.gamenative` prefix in
the id: prebuilt `libkgslshim.so` matches on that substring.

## Architecture (planned, in build order)

1. **Bottom-screen panels**: Android UI in a non-focusable `Presentation` on the secondary display
   (existing pattern: `app/gamenative/externaldisplay/`). The controller must always stay with the game.
2. **Region views**: crop rectangles of the game frame onto the bottom screen; second render thread at the
   bottom screen's 60 Hz; **zero-copy** (share `AHardwareBuffer`s, never `glReadPixels`); redraw only on
   change. Plus top-screen masks, a touch layout editor, and touch passthrough (tap -> click at source coords).
3. **Window routing**: send specific X windows (by title/class) to the bottom screen. Frames already arrive
   zero-copy via DRI3 `AHardwareBuffer`s + the Present extension.
4. **Bridge**: local socket between Windows-side mods (inside Wine) and Android panels; compact binary
   messages, state capped ~30/s, commands back to the game; native panels from a JSON schema, WebView optional.
5. **Engine adapters**: Ren'Py, RPG Maker MV/MZ, Unity (uGUI/UI Toolkit), Godot, Unreal (UMG); later a DXVK
   "UI split" that sends UI draws to a separate layer.
6. **Community packages**: Git-based index, per-game presets that auto-apply (data-only by default, code mods
   need consent), device profiles, signing, compatibility reports.

Performance rules: the CPU is the bottleneck (Box64/FEX), so keep devkit work off it: low-priority threads,
no CPU frame copies, data over pixels, idle at 0 fps when nothing changes.

## Conventions

- Logcat tag `GNDuo` (Timber). Nothing in `app.gamenative.duo` may crash or stall a game session: catch and
  log at every entry point.
- Branches: `master` mirrors upstream untouched; `devkit` is the working branch; features go in `duo/<name>`
  branches with a PR into `devkit`.
- Kotlin style is enforced by kotlinter (`.editorconfig`, android_studio style, 140 cols, trailing commas).

## Build, install, debug

```bash
./gradlew :app:assembleModernDebug          # APK: app/build/outputs/apk/modern/debug/
./gradlew :app:installModernDebug           # build + install on the connected device
adb logcat -s GNDuo                         # devkit logs
./gradlew :app:lintKotlin                   # style check
```

- CI: `.github/workflows/duo-debug-build.yml` builds the APK on pushes to `devkit`/`duo/**` (artifact on the run);
  `duo-upstream-check.yml` nightly test-merges upstream `master` (needs `devkit` as the default branch).
- All builds sign with `duo/debug.keystore` (debug-only, deliberately committed) so CI and local APKs update
  each other. For local builds: `cp duo/debug.keystore ~/.android/debug.keystore` (back up your own first).
- A `local.properties` with dummy `POSTHOG_API_KEY`/`POSTHOG_HOST` is enough to build.

## Known issues / open questions

- Deep-link schemes (`gamenative://`, `app.gamenative://` for Nexus login) are shared with official GameNative,
  so Android may ask which app to open them with.
- Frontends (Beacon, ES-DE) must target package `app.gamenative.duo` to launch games in the fork.
- Some upstream unit tests assert `/data/data/app.gamenative` paths; under the duo id they may fail.
- The fork still uses GameNative's servers (`api.gamenative.app`, `downloads.gamenative.app`). Fine for personal
  testing; **get the maintainers' permission or run our own before any public release.**
- Never add anything that bypasses or weakens DRM.

## Upstream candidates

- `AppPaths` (package-independent data paths) — fixes upstream's `.gold` build too.
- A "second display host" extension point + renderer frame-export hook, so the devkit could become a plugin.
