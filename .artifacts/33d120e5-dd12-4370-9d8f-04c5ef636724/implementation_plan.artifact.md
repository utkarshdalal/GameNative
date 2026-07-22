# Implementation Plan - Cross-Store Game Awareness

Enhance the game library and detail screens to provide better information about games owned across multiple stores (Steam, GOG, Epic, Amazon) and allow quick switching between them.

## User Review Required

> [!IMPORTANT]
> **Matching Strategy**: Games will be matched across stores primarily by name using existing normalization (removing accents and case-insensitive matching). This is the same logic used for "Best Config" matching.
> **Library Grid**: For now, I propose keeping multiple entries in the library grid if they exist in different stores, but adding badges/icons to show other available sources. Merging them into a single card is a larger architectural change that might conflict with store-specific features (like different Steam AppIDs for different editions).

## Proposed Changes

### Data & ViewModel

#### [MODIFY] [LibraryItem.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/data/LibraryItem.kt)
- Add `otherSources: List<GameSource> = emptyList()` to `LibraryItem` to store sibling sources.
- Add `isInstalledOnOtherSource: Boolean = false` to indicate if the game is installed via a different store.

#### [MODIFY] [LibraryViewModel.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt)
- Update `onFilterApps()` to identify games with the same name across different sources.
- Populate the new fields in `LibraryItem` during the combination phase.
- Logic:
  1. Group all `LibraryEntry` items by normalized name.
  2. For each entry, determine which other sources have the same game.
  3. Check if any of those sibling entries are installed.

### UI Components

#### [MODIFY] [LibraryAppItem.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt)
- Update `AppItem` (and its children `ListViewCard`, `GridViewCard`) to display icons for all available sources.
- Show an "Installed" indicator if the game is installed on *any* source.

#### [MODIFY] [LibraryGridCard.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryGridCard.kt)
- Add a row of small store icons to the card layout (e.g., in the top-right or near the title).

#### [MODIFY] [LibraryListCard.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListCard.kt)
- Display available store icons in the list item.

### Game Detail Screen

#### [MODIFY] [LibraryAppScreen.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/LibraryAppScreen.kt)
- Add a "Available on other stores" section to the `AppScreenContent`.
- This section will show icons for other stores where the user owns the game.
- Clicking a store icon will navigate to that store's version of the game.
- If the current store's version is NOT installed, but another store's version IS, add a prominent banner: "This game is already installed via [Store Name]".

## Verification Plan

### Automated Tests
- N/A (UI-heavy changes), but I will verify that the `LibraryViewModel` logic correctly identifies siblings in a debug run if possible, or by careful manual verification of the state.

### Manual Verification
1.  **Test Case 1**: Same game owned on Steam and Epic.
    - Verify both items in the library show both Steam and Epic icons.
    - Open Steam version: verify Epic icon is shown in "Other stores" section.
    - Click Epic icon: verify it switches to the Epic version's detail page.
2.  **Test Case 2**: Game installed on Steam but viewed on Epic store page.
    - Verify Epic detail page shows "Already installed via Steam".
3.  **Test Case 3**: Search filtering.
    - Verify cross-store info still works when the list is filtered.
