# Walkthrough - Cross-Store Awareness and Source Switcher Improvements

I have implemented several improvements to the cross-store awareness and source switcher feature based on the feedback from PR #1760. These changes enhance the reliability of game matching, fix UI logic errors, and improve accessibility and layout.

## Changes

### Utilities

#### [StringUtils.kt](file:///app/src/main/java/app/gamenative/utils/StringUtils.kt)
- Added `normalizeForComparison()` extension function. This function provides a robust way to match game titles across different stores by:
    - Removing accents (diacritics).
    - Converting to lowercase.
    - **Stripping all non-alphanumeric characters** (e.g., ™, ®, symbols, punctuation).
    - Trimming whitespace.
- Added KDoc documentation to multiple string utility functions.

#### [StringUtilsTest.kt](file:///app/src/test/java/app/gamenative/utils/StringUtilsTest.kt)
- Added unit tests for `normalizeForComparison()` to ensure it correctly handles symbols and case-insensitive matching (e.g., "The Witcher® 3" matches "THE WITCHER 3").

### Library Logic

#### [LibraryViewModel.kt](file:///app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt)
- Refactored `onFilterApps` to use a **global sibling lookup**. Sibling information is now calculated from the full, unpaginated library data *before* any search or tab filters are applied. This ensures the source switcher always knows about all available platforms for a game.
- Updated sibling filtering logic to correctly identify games as siblings only if they have the same normalized name but a **different `appId` AND a different `gameSource`**.
- Added KDoc to `LibraryViewModel` and `onFilterApps`.

### UI Components

#### [LibraryListCard.kt](file:///app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListCard.kt)
- Fixed a logic error in `InstallStatusBadge` where non-Steam games (GOG, Epic, etc.) would always show "Ready" even if they were already installed on another platform. The "Installed elsewhere" status now has higher priority than the generic store fallback.
- Added KDoc to `InstallStatusBadge`.

#### [LibraryGridCard.kt](file:///app/src/main/java/app/gamenative/ui/screen/library/components/LibraryGridCard.kt)
- Added specific **accessibility labels** for the "installed elsewhere" status icon in `GridStatusIcons`, improving the experience for screen reader users.
- Added KDoc to `GridStatusIcons`.

#### [LibraryAppScreen.kt](file:///app/src/main/java/app/gamenative/ui/screen/library/LibraryAppScreen.kt)
- Improved the "Also available on" source switcher by making the platform row **horizontally scrollable**. This prevents layout clipping for games available on many platforms.
- Added KDoc to `AppScreenContent`.

## Verification Results

### Automated Tests
- Unit tests for `normalizeForComparison()` were added to `StringUtilsTest.kt` to cover the new normalization logic.
- Tested cases:
    - `The Witcher® 3` -> `thewitcher3`
    - `THE WITCHER 3` -> `thewitcher3`
    - `the-witcher-3` -> `thewitcher3`
    - `Cyberpunk 2077™` -> `cyberpunk2077`

### Manual Code Review
- Verified that the `when` expression in `LibraryListCard.kt` now correctly prioritizes `isInstalledOnOtherSource`.
- Verified that `LibraryViewModel.kt` now builds its sibling map from all owned games across all supported sources.
- Verified that the UI components now use the newly added `normalizeForComparison()` for consistent matching.
