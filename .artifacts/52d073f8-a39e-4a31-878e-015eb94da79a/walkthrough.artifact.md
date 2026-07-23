# Walkthrough: Global "Force Offline" Mode

Implemented a global "Force Offline Mode" that completely silences the app's network activity and UI notifications.

## Changes Made

### Core Logic & State
- **[PrefManager.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/PrefManager.kt):** Added a global `forceOffline` preference.
- **[NetworkMonitor.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/NetworkMonitor.kt):** Refactored to allow manual `update()` calls and force `hasInternet` to `false` when the global offline mode is active.
- **[MainViewModel.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/model/MainViewModel.kt):** Updated `isOffline` state to respect the global `forceOffline` preference.

### UI & UX
- **[SettingsGroupInterface.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt):** Added a new toggle in the Interface settings to enable/disable Force Offline Mode.
- **[PluviaMain.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/PluviaMain.kt):**
    - Gated Steam, Epic, GOG, and Amazon service startups.
    - Suppressed the connection status banner when in force offline mode.
    - Bypassed the platform login wait during game launches when offline.
    - Skipped the automatic update check.

### Service Safety Guards
- Added immediate `stopSelf()` checks to `onStartCommand` for all platform services ([Steam](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/service/SteamService.kt), [Epic](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/service/epic/EpicService.kt), [GOG](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/service/gog/GOGService.kt), [Amazon](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/service/amazon/AmazonService.kt)) to ensure no background activity occurs even if a service is accidentally triggered.

## Verification Results

### Manual Verification Path
1.  Navigate to **Settings > Interface**.
2.  Enable **Force Offline Mode**.
3.  Observe that no "Connecting..." banner appears.
4.  Launch a game; verify it proceeds to the booting splash immediately without waiting for platform login.
5.  Check logs to confirm services are stopped or skipped.

> [!TIP]
> This mode is process-wide. When enabled, the app will act as if it has no internet connection even if the system reports otherwise.
