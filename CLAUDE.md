# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development

Build via Android Studio (standard debug build). No special Gradle tasks are needed for day-to-day development.

Optional: add a SteamGridDB API key to `local.properties` for Custom Games image fetching:
```
STEAMGRIDDB_API_KEY=your_api_key
```

## Translations

**Before opening any PR:** any new string added to `app/src/main/res/values/strings.xml` must also be added to all 13 language files:
`values-da`, `values-de`, `values-es`, `values-fr`, `values-it`, `values-ko`, `values-pl`, `values-pt-rBR`, `values-ro`, `values-ru`, `values-uk`, `values-zh-rCN`, `values-zh-rTW`

## Architecture

The app is a fork of [Winlator](https://github.com/brunodev85/winlator) extended with Steam, GOG, Epic, and Amazon game store integrations. It runs Windows games via Wine inside an XServer rendered to an Android `SurfaceView`.

**Key layers:**

- `service/SteamService` - singleton managing the Steam client connection, game installs, downloads, and session state. Exposes companion object functions and `StateFlow`/`AtomicBoolean` fields for reactive UI.
- `service/DownloadService` - manages download queues and exposes base storage paths (`baseDataDirPath`, `baseCacheDirPath`).
- `ui/PluviaMain.kt` - single-Activity Compose nav host; all screens are registered here as `composable()` routes using `PluviaScreen` sealed class destinations.
- `ui/screen/library/` - library browsing and game detail screens. `AppScreen` dispatches to source-specific `BaseAppScreen` subclasses (`SteamAppScreen`, `GOGAppScreen`, etc.) via a `remember`-keyed instance. `BaseAppScreen.Content()` assembles state and calls `AppScreenContent`; source-specific behavior is injected via open functions (e.g. `buildAchievementsSection`).
- `ui/screen/xserver/XServerScreen.kt` - the in-game screen. Owns `XServerView`, `WinHandler`, and all Wine/X11 lifecycle. Dialogs that need access to the live game session (e.g. session-conflict dialog) live here, not in `PluviaMain`.
- `utils/ContainerStorageManager` - scans Wine containers and game install paths across all stores, computes sizes, and handles move operations.
- `PrefManager` - shared preferences singleton; `externalStoragePath` is the user-configured external storage root.

**Navigation:** `PluviaScreen` sealed class defines all routes. Navigate by calling `onNavigateRoute(PluviaScreen.Foo.route(args))` threaded down from `LibraryScreen` → `LibraryDetailPane` → `AppScreen` → `BaseAppScreen.Content()`.

**Events:** `PluviaApp.events` is a shared `MutableSharedFlow<Any>` used to broadcast cross-component events (e.g. `SteamEvent`, `AndroidEvent`). Collect in composables via `LaunchedEffect`.

**Dependency injection:** Hilt with `@EntryPoint` used in non-Hilt contexts (e.g. `ContainerStorageManager` uses `EntryPointAccessors` to get DAOs).
