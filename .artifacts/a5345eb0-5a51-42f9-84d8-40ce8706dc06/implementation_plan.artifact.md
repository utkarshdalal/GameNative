# Implementation Plan - Resolution Enhancements and Adaptive Scaling Fixes

This plan addresses recommendations from PR #1759 reviews to improve resolution selection logic, ensure driver compatibility, and maintain UI consistency.

## User Review Required

> [!IMPORTANT]
> The aspect ratio calculation uses a standard GCD approach. Some non-standard resolutions might result in unusual ratio labels (e.g., "13:6" instead of "19.5:9"). I have added a specific check for 13:6 to display as 19.5:9 as it is a common mobile ratio.

## Proposed Changes

### [Resources]

#### [MODIFY] [arrays.xml](app/src/main/res/values/arrays.xml)
- Update `1200x540 (16:9)` to `1200x540 (20:9)`.
- Update `1600x720 (16:9)` to `1600x720 (20:9)`.

---

### [UI Components]

#### [MODIFY] [ContainerConfigDialog.kt](app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigDialog.kt)
- Implement `toEven(value: Float): Int` helper to ensure dimensions are rounded to the nearest even number, preventing rendering artifacts on some GPU drivers.
- Implement `calculateAspectRatio(width: Int, height: Int): String` using GCD to determine the simplified aspect ratio.
- Update `rememberContainerConfigDialogStaticData` to:
    - Use `toEven` for `halfRes` and `optimizedRes` calculations.
    - Include the aspect ratio in the `adaptiveScreenSizes` labels (e.g., `"2400x1080 (20:9, Native)"`).

## Verification Plan

### Automated Tests
- I will verify the code compiles by running a build task if possible, or at least ensuring no syntax errors are introduced.
- Since this involves UI logic in Composable `remember` blocks, manual verification on a device would be ideal, but I will double-check the logic.

### Manual Verification
- Verify that `1200x540` and `1600x720` now show `(20:9)` in the resolution dropdown.
- Verify that "Native", "Optimized", and "Half" options now include their calculated aspect ratios.
- Verify that all dynamically calculated resolutions are even numbers.
