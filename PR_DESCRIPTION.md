# Hide Status Bar When Not In Game

## Summary

Adds a setting to hide the Android status bar when browsing the app (game list, settings, etc.) while keeping it visible during gameplay.

## Changes

- Added `hideStatusBarWhenNotInGame` preference in `PrefManager.kt`
- Updated `PluviaMain.kt` to hide/show status bar based on the preference when not in game
- Added toggle switch in settings with restart confirmation dialog

## Behavior

- Default: Status bar visible (backward compatible)
- When enabled: Status bar hidden in library, settings, and other non-game screens
- Requires app restart to take effect
- Does not affect status bar visibility during gameplay

