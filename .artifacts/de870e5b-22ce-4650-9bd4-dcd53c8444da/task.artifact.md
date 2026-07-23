# Tasks - PR 1759 Finalization

- [x] Refactor resolution logic in `ContainerConfigDialog.kt`
    - [x] Move `evenRound`, `gcd`, `calculateAspectRatio` to top level
    - [x] Apply `evenRound` to custom resolutions
    - [x] Round dimensions before aspect ratio validation in `GeneralTab.kt` to prevent square resolutions (e.g., 4x3 -> 4x4)
- [x] Add KDoc to Tab Composables
    - [x] `EmulationTabContent`
    - [x] `ControllerTabContent`
    - [x] `WineTabContent`
    - [x] `WinComponentsTabContent`
    - [x] `DrivesTabContent`
    - [x] `AdvancedTabContent`
- [x] Verify `arrays.xml` aspect ratios
- [x] Update artifacts and verify links
- [x] UI Verification via Compose Preview (Attempted, manual logic verification performed)
