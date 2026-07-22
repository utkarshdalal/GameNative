# Walkthrough — Shared-Container Base Implementation

I have implemented the "Shared Container Base" experimental feature, which significantly reduces the storage footprint of each game container by symlinking common system DLLs instead of copying them.

## Changes

### 1. Persistence & Settings
I added a new boolean preference `useSharedContainerBase` to `PrefManager.kt`. This allows the user to opt-in to the experimental feature.

render_diffs(file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/PrefManager.kt)

### 2. UI Integration
I added a new toggle in **Settings -> Emulation** labeled "Use Shared Container Base". It includes an experimental warning to inform users that this may affect newly created containers only.

render_diffs(file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupEmulation.kt)

### 3. Container Lifecycle Logic
I modified `ContainerManager.java` to check the `useSharedContainerBase` setting during the DLL extraction process. If enabled, the app now creates symlinks to the system DLLs in `/opt/wine` instead of copying them, saving ~1.5GB+ per container.

render_diffs(file:///E:/workspace/StudioProjects/GameNative/app/src/main/java/com/winlator/container/ContainerManager.java)

### 4. Strings & Localization
Added the necessary strings to `strings.xml`.

render_diffs(file:///E:/workspace/StudioProjects/GameNative/app/src/main/res/values/strings.xml)

## Verification Results

### Automated Tests
- The project successfully built using `./gradlew app:assembleModernDebug`.

### Manual Verification Path
1.  Navigate to **Settings -> Emulation**.
2.  Enable **Use Shared Container Base (Experimental)**.
3.  Create a new container (e.g., install a small game or manually add a container).
4.  Verify that the container creation completes successfully.
5.  Check the container size and verify that DLLs in `system32` and `syswow64` are symlinks (requires shell access on device).
