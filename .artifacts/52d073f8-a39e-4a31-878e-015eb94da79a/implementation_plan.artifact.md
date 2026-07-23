# Implementation Plan: Global "Force Offline" Mode

This plan introduces a global toggle to force the application into an offline state, suppressing all network activity and connection-related UI disturbances.

## User Review Required

> [!IMPORTANT]
> **Global Override:** When "Force Offline" is enabled, the app will report no internet connection to all internal components. This will prevent library syncing, workshop downloads, and cloud save uploads/downloads until disabled.

> [!NOTE]
> **Visual Indicator:** Should we add a small "Offline" badge to the main screen header when this mode is active to prevent user confusion? (Proposed as a "basic enhancement").

## Proposed Changes

### Core Logic & State

#### [MODIFY] [PrefManager.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/PrefManager.kt)
- Add `forceOffline` boolean preference (default: `false`).

#### [MODIFY] [NetworkMonitor.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/NetworkMonitor.kt)
- Update `hasInternet` and `hasWifiOrEthernet` logic to return `false` if `PrefManager.forceOffline` is `true`.
- Ensure it reactively updates when the preference changes.

#### [MODIFY] [MainViewModel.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/model/MainViewModel.kt)
- Initialize the `isOffline` StateFlow using `PrefManager.forceOffline`.
- Ensure `continueOffline()` and other state transitions respect the global toggle.

---

### UI & UX

#### [MODIFY] [SettingsGroupInterface.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt)
- Add a new `SettingsSwitch` for "Force Offline Mode".
- Description: "Disable all network connections and suppress connection alerts."

#### [MODIFY] [PluviaMain.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/PluviaMain.kt)
- Gate service startups (Steam, Epic, GOG, Amazon) with `!PrefManager.forceOffline`.
- Skip `UpdateChecker` if `forceOffline` is active.
- Suppress `ConnectionStatusBanner` visibility when `forceOffline` is active.
- In `launchIntentApp`, skip `SteamUtils.awaitSteamLogin` if `forceOffline` is true.

---

### Platform Services

#### [MODIFY] [SteamService.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/service/SteamService.kt) (and Epic/GOG/Amazon equivalents)
- Add a safety check in `onStartCommand` to immediately `stopSelf()` if `forceOffline` is true (to catch any accidental starts).

## Verification Plan

### Automated Tests
- N/A (Build failed on env, will rely on manual verification and code correctness).

### Manual Verification
1.  **Enable Force Offline:** Verify that the "Connecting to Steam..." banner does not appear.
2.  **Service Check:** Verify (via logs) that no platform services attempt to start.
3.  **Launch Game:** Launch a Steam game. It should bypass the login wait and proceed in offline mode immediately.
4.  **UI Feedback:** Check that the Library screens show "Offline" status/buttons appropriately.
5.  **Disable Force Offline:** Verify that the app immediately attempts to reconnect and syncs the library.
