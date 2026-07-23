# Implementation Plan - Cross-Store Awareness and Source Switcher Improvements

This plan addresses the feedback from PR #1760 regarding the "cross-store awareness and source switcher" feature. It aims to improve name normalization, sibling filtering logic, data scope for source switching, and UI accessibility/layout.

## Proposed Changes

### [Component Name] Utilities

#### [MODIFY] [StringUtils.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/utils/StringUtils.kt)
- Add `normalizeForComparison()` extension function to `String` (or `CharSequence`).
- This function will:
    1. Unaccent the string.
    2. Convert to lowercase.
    3. Remove all non-alphanumeric characters (including symbols like ®).
    4. Trim whitespace.

### [Component Name] Library Logic

#### [MODIFY] [LibraryViewModel.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt)
- Update sibling grouping logic in `onFilterApps`:
    - Calculate a "global" mapping of all owned games (from Steam, GOG, Epic, Amazon, and Custom) indexed by the new `normalizeForComparison()` result.
    - This global mapping should be created from the full lists (`appList`, `gogGameList`, etc.) BEFORE any filters (search, tabs, collections) are applied.
    - Use this global mapping to populate `otherSources` and `isInstalledOnOtherSource` for each `LibraryItem`.
    - Fix sibling filtering logic: A sibling is a game with the same normalized name but a different `appId` **AND** a different `gameSource`.

### [Component Name] UI Components

#### [MODIFY] [LibraryListCard.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListCard.kt)
- Fix logic error in `InstallStatusBadge`: Move the check for `appInfo.isInstalledOnOtherSource` above the `!isSteam` check so that non-Steam games can correctly show "Installed elsewhere".

#### [MODIFY] [LibraryGridCard.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryGridCard.kt)
- Add an accessibility label for the "installed elsewhere" status icon in `GridStatusIcons`.

#### [MODIFY] [LibraryAppScreen.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/LibraryAppScreen.kt)
- Make the "Also available on" row horizontally scrollable in `AppScreenContent` to handle cases where a game is available on many platforms.

### [Component Name] Documentation

#### [MODIFY] Multiple Files
- Add KDoc to public functions and classes modified in this task to improve docstring coverage, as recommended in the PR review.

### [Component Name] Tests

#### [NEW] [StringUtilsTest.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/test/java/app/gamenative/utils/StringUtilsTest.kt) (Update)
- Add test cases for `normalizeForComparison()` to ensure it correctly links games like "The Witcher® 3" and "THE WITCHER 3".

## Verification Plan

### Automated Tests
- Run `StringUtilsTest` to verify the normalization logic.
- Command: `./gradlew :app:testDebugUnitTest --tests "app.gamenative.utils.StringUtilsTest"`

### Manual Verification
- Since I cannot run the app, I will:
    1. Carefully review the modified `when` expression in `LibraryListCard.kt`.
    2. Verify that the "Also available on" `Row` in `LibraryAppScreen.kt` now has a `horizontalScroll` modifier.
    3. Verify that `LibraryViewModel.kt` uses the full library data for sibling linking.
