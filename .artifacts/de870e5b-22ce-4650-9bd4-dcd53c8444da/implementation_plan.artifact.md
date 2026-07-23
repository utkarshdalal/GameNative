# PR #1759 (Resolution Enhancements) Finalization Plan

This plan addresses mathematical correctness for GPU drivers, UI consistency, documentation standards, and automated review feedback for the Resolution Enhancements pull request.

## User Review Required

> [!IMPORTANT]
> The pixel rounding logic will now use `evenRound` (nearest even integer) instead of `toEven` (round down to even). This ensures framebuffer dimensions are always even, which is a requirement for many mobile GPU drivers to avoid artifacts or crashes.

> [!NOTE]
> Several hardcoded strings related to language names and aspect ratios will be moved to `strings.xml` to meet project standards and fix bot feedback.

## Proposed Changes

### UI Components

#### [MODIFY] [ContainerConfigDialog.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigDialog.kt)
- Replace `toEven` with `evenRound(value: Float): Int` using the formula `(value / 2.0f).roundToInt() * 2`.
- Apply `evenRound` to `nativeWidth` and `nativeHeight` at the beginning of `rememberContainerConfigDialogStaticData`.
- Use rounded values for all scaling calculations (Optimized, Half).
- Update `calculateAspectRatio` to use rounded values and fetch aspect ratio strings from resources.
- Add KDoc to `evenRound`, `gcd`, `calculateAspectRatio`, `rememberContainerConfigDialogStaticData`, `ContainerConfigDialog`, and `ExecutablePathDropdown`.

#### [MODIFY] [GeneralTab.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/component/dialog/GeneralTab.kt)
- Update `displayNameForLanguage` to use string resources for localized language names.
- Add KDoc to `GeneralTabContent`.

#### [MODIFY] [GraphicsTab.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/component/dialog/GraphicsTab.kt)
- Add KDoc to `GraphicsTabContent`.

### Resources

#### [MODIFY] [strings.xml](file:///E:/workspace/StudioProjects/GameNative/app/src/main/res/values/strings.xml)
- Add missing string resources for:
    - Language names (Simplified Chinese, Traditional Chinese, Korean, Spanish (Latin America), Portuguese (Brazil)).
    - Aspect ratios (19.5:9, 21.5:9).

## Verification Plan

### Automated Tests
- Build the project to ensure no compilation errors.
- Verify KDoc coverage manually by checking the modified files.

### Manual Verification
- Deploy the app to a device or emulator.
- Open the Container Configuration dialog.
- Verify that "Native", "Optimized", and "Half" resolutions display even numbers in the dropdown.
- Verify that the aspect ratios (e.g., 19.5:9) are displayed correctly.
- Verify that selecting a custom resolution still works and enforces the rules (greater than 0, width > height).
