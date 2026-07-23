# Pull Request: Global "Force Offline" Mode

## Description
This PR introduces a global "Force Offline Mode" toggle in the Interface settings. When enabled, the application will act as if there is no internet connection, silencing all background platform services (Steam, Epic, GOG, Amazon) and suppressing connection-related UI alerts (banners and dialogs).

This is particularly useful for users playing on handhelds in areas with spotty or no internet, as it avoids the "Connecting..." timeouts and allows games to launch immediately in offline mode.

## Changes
- **Global Preference:** Added `forceOffline` to `PrefManager`.
- **Reactive Network State:** Updated `NetworkMonitor` to force `hasInternet` to `false` when the toggle is active. UI components observing this flow will naturally adapt to an offline state.
- **Service Gating:**
    - Gated service startup in `PluviaMain`.
    - Added safety checks in `onStartCommand` for `SteamService`, `EpicService`, `GOGService`, and `AmazonService` to immediately stop if the mode is active.
- **UI Enhancements:**
    - Suppressed `ConnectionStatusBanner` in `PluviaMain`.
    - Added "Force Offline Mode" toggle to `SettingsGroupInterface`.
    - Bypassed platform login handshakes (e.g., `SteamUtils.awaitSteamLogin`) for faster game boots when forced offline.
    - Skipped automatic update checks.
- **Localization:** Added English and Spanish strings for the new setting.

## Verification
- Verified that enabling the toggle stops all service reconnection attempts.
- Verified that games launch immediately without attempting a network login.
- Verified that the "Connecting..." banner is hidden when the toggle is active.

## Out of Scope Check
While this is a preference-driven behavior change, it directly addresses common feedback regarding the "noisy" nature of the app when internet is unavailable. It is implemented in a non-breaking way that defaults to `false`.
