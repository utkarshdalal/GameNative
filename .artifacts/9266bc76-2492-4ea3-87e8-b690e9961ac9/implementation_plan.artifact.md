# Implementation Plan - Finalize and Polish PR #1758

This plan covers the final steps to polish and stabilize PR #1758, addressing race conditions, UI issues, and documentation gaps.

## User Review Required

> [!IMPORTANT]
> The documentation coverage will be increased significantly. Please verify if any specific documentation style (other than standard KDoc/Javadoc) is required.

## Proposed Changes

### Core Utils & Logic

#### [MODIFY] [CustomGameCache.kt](file:///app/src/main/java/app/gamenative/utils/CustomGameCache.kt)
- Add thread-safety to `getOrRebuildCache` and `addEntry` using a `synchronized` block or `Mutex`.
- This prevents concurrent disk scans when multiple icons are resolved simultaneously.

#### [MODIFY] [ContainerManager.java](file:///app/src/main/java/com/winlator/container/ContainerManager.java)
- Make the constructor `private`.
- Make `getInstance(Context)` thread-safe using a synchronized block.
- Ensure the class is `final` to strictly enforce the Singleton pattern.
- Double-check all usages to ensure no reflection is used to bypass the private constructor (though unlikely).

#### [MODIFY] [CustomGameScanner.kt](file:///app/src/main/java/app/gamenative/utils/CustomGameScanner.kt)
- Wrap external storage access in `try-catch` blocks and add `exists()` checks to handle "hot-plugged" drives gracefully.
- Add KDoc to public methods to increase coverage.

### UI Components

#### [MODIFY] [LibraryListPane.kt](file:///app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt)
- Fix overlapping horizontal dividers in `LIST` layout by wrapping the divider and the item `Box` in a `Column`.
- Ensure proper spacing and alignment for the divider.

### Documentation & Cleanup

#### [MODIFY] [task.artifact.md](file:///.artifacts/9266bc76-2492-4ea3-87e8-b690e9961ac9/task.artifact.md)
- Fix non-standard Markdown syntax (remove backticks from task list items).

#### [MODIFY] Various Files
- Increase docstring coverage to >80% in:
    - `CustomGameScanner.kt`
    - `CustomGameCache.kt`
    - `ContainerManager.java`
    - `LibraryListCard.kt`
    - `LibraryAppItem.kt`
    - `LibraryListPane.kt`
- Replace absolute machine-specific paths in comments/docs with relative repository links (e.g., `[ContainerManager.java](file:///com/winlator/container/ContainerManager.java)`).

## Verification Plan

### Automated Tests
- Run existing unit tests (if any) related to `ContainerManager` and `CustomGameScanner`.
- I will check for test files and run them.

### Manual Verification
- Deploy the app to a device/emulator.
- Test the `LIST` layout and verify horizontal dividers are correctly placed and not overlapping.
- Test "hot-plugging" by simulating storage changes (if possible) or verifying that missing paths don't cause crashes.
- Verify that custom game icons load correctly without triggering multiple scans (check logs).
