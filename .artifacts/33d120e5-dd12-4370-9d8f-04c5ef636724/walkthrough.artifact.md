# Walkthrough - Cross-Store Game Awareness

I have enhanced the game library and detail screens to provide information about games owned across multiple stores and allow quick switching between them.

## Changes Made

### Data Layer
- **[LibraryItem.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/data/LibraryItem.kt)**: Added `otherSources` list and `isInstalledOnOtherSource` flag to the main data model.
- **[LibraryViewModel.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt)**: Updated the filtering and combination logic to identify games with the same name across different sources (Steam, GOG, Epic, Amazon). It now automatically links these "sibling" entries.

### Library UI
- **[LibraryAppItem.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt)**:
    - Added `OtherSourcesIcons` component to display a row of small store icons.
    - Enhanced `GameSourceIcon` to support custom tinting.
- **[LibraryGridCard.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryGridCard.kt)**:
    - Added a row of store icons in the top-right corner of grid cards if a game is available on multiple stores.
    - Updated the "Installed" checkmark to show even if the game is installed via a different store (with a slightly dimmed color).
- **[LibraryListCard.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListCard.kt)**:
    - Added store icons to the list view items.
    - Updated status text to show "Installed elsewhere" if applicable.

### Game Detail Screen
- **[LibraryAppScreen.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/LibraryAppScreen.kt)**:
    - Added a **"Also available on"** section in the game information area, allowing users to jump directly to the same game on a different store.
    - Added a prominent **banner** that appears if you are viewing a store page for a game that is already installed via another source.

### Resources
- **[strings.xml](file:///E:/workspace/StudioProjects/GameNative/app/src/main/res/values/strings.xml)**: Added new localized strings for the cross-store features.

## Verification Results

### Build State
- Successfully built the `:app` module.

### Core Logic
- Verified that games are matched using normalized names (removing accents and case-insensitive), ensuring consistent linking between stores like "THE WITCHER 3" (Epic) and "The Witcher® 3" (Steam).

## How to Test
1.  Ensure you are logged into at least two stores (e.g., Steam and Epic) and own the same game on both.
2.  Open the Library.
3.  Observe that both entries for the game show icons for both Steam and Epic.
4.  Open the Steam version's detail page.
5.  Click the Epic icon in the "Also available on" section to switch to the Epic version's page.
6.  If you have the game installed on Steam but not on Epic, the Epic page should show a banner saying "This game is already installed via Steam".
