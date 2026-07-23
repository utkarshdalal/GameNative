# Implementation Plan - Fix Crashes, ANRs, and UI Stability in Library List View

This plan aims to implement the changes and feedback from [PR #1758](https://github.com/utkarshdalal/GameNative/pull/1758) to resolve critical application crashes and ANRs, especially when games are stored on external storage.

## User Review Required

> [!IMPORTANT]
> The `ContainerManager` will be converted to a strict singleton. This involves making its constructor private and updating all instantiation sites to use `getInstance(Context)`.

## Proposed Changes

### Core Infrastructure

#### [MODIFY] [ContainerManager.java](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/com/winlator/container/ContainerManager.java)
- Make the constructor `private`.
- Make `getInstance(Context)` thread-safe using a synchronized block.

#### [MODIFY] [AdrenotoolsManager.java](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/com/winlator/contents/AdrenotoolsManager.java)
- Replace `new ContainerManager(context)` with `ContainerManager.getInstance(context)`.

#### [MODIFY] [ImageFsInstaller.java](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/com/winlator/xenvironment/ImageFsInstaller.java)
- Replace `new ContainerManager(context)` with `ContainerManager.getInstance(context)`.

#### [MODIFY] [PluviaMain.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/PluviaMain.kt)
- Replace `ContainerManager(context)` with `ContainerManager.getInstance(context)`.

#### [MODIFY] [XServerScreen.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt)
- Replace `ContainerManager(context)` with `ContainerManager.getInstance(context)`.

#### [MODIFY] [ContainerUtils.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/utils/ContainerUtils.kt)
- Replace all `ContainerManager(context)` calls with `ContainerManager.getInstance(context)`.

---

### Library UI Components

#### [MODIFY] [LibraryListCard.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListCard.kt)
- Add `imageRefreshCounter: Long` parameter to `ListViewCard`.
- Add `imageRefreshCounter` to the `produceState` keys for icon loading. This ensures icons are re-fetched if the refresh counter changes (e.g., when external storage becomes ready).

#### [MODIFY] [LibraryAppItem.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt)
- Pass the `imageRefreshCounter` from `AppItem` to `ListViewCard`.

#### [MODIFY] [LibraryListPane.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt)
- Move the `HorizontalDivider` logic out of the animated `Box` and `Column` to prevent it from being part of the item cell's animated alpha and touch area.
- Position the divider above the item's animated `Box`.

---

### Verification Plan

### Automated Tests
- Run a build to ensure all `ContainerManager` references are correctly updated and the private constructor doesn't break anything.
- `gradlew :app:assembleDebug`

### Manual Verification
- Verify that the Library screen loads correctly.
- Verify that switching between List and Grid layouts works as expected.
- Verify that icons load correctly in List view.
