# Implementation Plan - Resolution Enhancement and Localization (PR #1759)

This plan documents the resolution enhancement logic and localization changes implemented in PR #1759. The goal is to ensure GPU driver compatibility by rounding resolutions to even integers and improving aspect ratio calculations for mobile devices.

## Proposed Changes

### UI Logic & Utilities

#### [MODIFY] [ContainerConfigDialog.kt](app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigDialog.kt)
- Implement `evenRound`, `gcd`, and `calculateAspectRatio` as top-level internal functions.
- Ensure all custom resolutions are rounded to the nearest even integer.

### Resource Localization

#### [MODIFY] [arrays.xml](app/src/main/res/values/arrays.xml)
- Update `screen_size_entries` to include correct 20:9 aspect ratios for 1200x540 and 1600x720.

### Documentation Improvements

#### [MODIFY] [AdvancedTab.kt](app/src/main/java/app/gamenative/ui/component/dialog/AdvancedTab.kt)
#### [MODIFY] [ControllerTab.kt](app/src/main/java/app/gamenative/ui/component/dialog/ControllerTab.kt)
#### [MODIFY] [EmulationTab.kt](app/src/main/java/app/gamenative/ui/component/dialog/EmulationTab.kt)
- Add comprehensive KDoc to tab composables and remove outdated claims regarding features like suspend policy, shooter mode, and TSO/x87 precision.

## Verification Plan

### Automated Tests
- Run [ResolutionUtilsTest.kt](app/src/test/java/app/gamenative/ui/component/dialog/ResolutionUtilsTest.kt) to verify rounding and aspect ratio logic.
- Command: `./gradlew :app:testDebugUnitTest --tests "app.gamenative.ui.component.dialog.ResolutionUtilsTest"`
- Run a build check to ensure no syntax errors.

### Manual Verification
- Verify custom resolution input in `ContainerConfigDialog`.
- Check KDoc coverage and accuracy for all modified tabs.
