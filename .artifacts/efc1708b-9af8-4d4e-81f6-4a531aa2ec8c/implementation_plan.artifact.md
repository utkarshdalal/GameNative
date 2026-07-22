# Phase 3 — Shared-Container Base Implementation Plan

This document outlines the implementation plan for the "Shared Container Base" experimental feature, which aims to reduce per-container storage overhead by symlinking common system DLLs instead of copying them.

## User Review Required

> [!WARNING]
> This feature is **experimental**. Symlinking system DLLs may cause issues with some installers if they attempt to overwrite these files. It is off by default and only affects newly created containers.

## Proposed Changes

The changes are organized by component:

### 1. Persistence & Settings

#### [MODIFY] [PrefManager.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/PrefManager.kt)
- Add `use_shared_container_base` boolean preference key.
- Default value: `false`.

#### [MODIFY] [strings.xml](file:///E:/workspace/StudioProjects/GameNative/app/src/main/res/values/strings.xml)
- Add new strings for the settings toggle:
  - `settings_emulation_shared_container_base_title`: "Use Shared Container Base"
  - `settings_emulation_shared_container_base_subtitle`: "[Experimental] Reduces storage by symlinking common system files. Affects new containers."

### 2. Container Lifecycle

#### [MODIFY] [ContainerManager.java](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/com/winlator/container/ContainerManager.java)
- Update `extractCommonDlls` overloads to check `PrefManager.getInstance().getUseSharedContainerBase()`.
- If `true`, use `FileUtils.symlink(srcFile, dstFile)` instead of `FileUtils.copy(srcFile, dstFile)`.
- **Note**: `FileUtils.symlink` already handles deleting existing files at the destination path.

### 3. UI Integration

#### [MODIFY] [SettingsGroupEmulation.kt](file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupEmulation.kt)
- Add a `SettingsSwitch` for "Use Shared Container Base" within the Emulation group.

---

## Verification Plan

### Automated Tests
- Run existing container creation tests to ensure no regressions.
- (Optional) Add a new test case in `ContainerManagerTest` that mocks `PrefManager` and verifies `Os.symlink` is called when the feature is enabled.

### Manual Verification
1.  **Baseline**: Create a new container with the feature OFF. Measure its size.
2.  **Toggle ON**: Enable "Use Shared Container Base" in Settings.
3.  **Experimental**: Create another container.
    - Verify it creation succeeds.
    - Measure its size (expect ~1.5GB reduction).
    - Use `ls -l` in a terminal (or check file properties) to verify files in `system32` are symlinks pointing to `/opt/wine/lib/wine/...`.
4.  **Isolation Check**: Delete the second container. Verify that the system files in `/opt/wine` are **not** deleted.
5.  **Game Launch**: Launch a game in the "shared" container to ensure basic functionality.
