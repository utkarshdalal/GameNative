# Tasks: Global "Force Offline" Mode

- [ ] Core Logic & State
    - [ ] Add `forceOffline` to `PrefManager.kt`
    - [ ] Update `NetworkMonitor.kt` to respect `forceOffline`
    - [ ] Update `MainViewModel.kt` to reflect global offline state
- [ ] UI Implementation
    - [ ] Add toggle to `SettingsGroupInterface.kt`
    - [ ] Update `PluviaMain.kt` for service gating and banner suppression
- [ ] Service Safety Guards
    - [ ] Add `forceOffline` check to `SteamService.kt`
    - [ ] Add `forceOffline` check to `EpicService.kt`
    - [ ] Add `forceOffline` check to `GOGService.kt`
    - [ ] Add `forceOffline` check to `AmazonService.kt`
- [ ] Verification
    - [ ] Review all changes for consistency
