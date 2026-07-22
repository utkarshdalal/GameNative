# Development Notes & Phase 1 Inventory

## Project Inventory

-   **AGP Version**: 8.8.0
-   **Gradle**: Kotlin DSL (`.kts`)
-   **Kotlin Version**: 2.1.21
-   **Java Version**: 17 (Target/Source Compatibility)
-   **SDKs**:
    -   `minSdk`: 26 (General) / 29 (Modern flavor)
    -   `targetSdk`: 28 (Legacy) / 36 (Modern)
    -   `compileSdk`: 36
-   **Build Variants**:
    -   `legacy`: Target SDK 28, broader storage access, Bionic `libredirect-bionic.so`.
    -   `modern`: Target SDK 36 (Android 11+), Bionic `libredirect-bionic-wx.so`.
    -   `XR` variants: Specific optimizations for Meta Horizon / Oculus Quest.
-   **Major Dependencies**:
    -   UI: Compose Material 3, Landscapist (Coil), Media3 (ExoPlayer).
    -   Core: Hilt, Room, DataStore, Coroutines, Timber.
    -   Store Integration: JavaSteam, JWTDecode, Auth0.
    -   Native: libarchive, zstd-jni, xz.

## Container Subsystem Detail

| Class | Path | Role |
| :--- | :--- | :--- |
| `Container` | `com.winlator.container.Container.java` | Data model for `.container` JSON and runtime state. |
| `ContainerManager` | `com.winlator.container.ContainerManager.java` | CRUD operations, symlink activation, skel extraction. |
| `ContainerUtils` | `app.gamenative.utils.ContainerUtils.kt` | Higher-level helpers, default config logic, and store mapping. |
| `ImageFs` | `com.winlator.xenvironment.ImageFs.java` | Path management for the Linux rootfs. |
| `ImageFsInstaller` | `com.winlator.xenvironment.ImageFsInstaller.java` | Installation, patching, and variant migration logic. |

### Key Observations for Phase 2
-   **DLL Duplication**: `common_dlls.json` lists hundreds of DLLs that are currently *copied* from `/opt/wine/lib/wine` into every container during creation.
-   **Pattern Extraction**: `container_pattern_gamenative.tzst` provides the base registry and directory structure.
-   **Active Symlink**: Only one container can be "active" as `home/xuser` at any time. This is a potential bottleneck for multi-game scenarios but simplifies storage pathing.

## Development Roadmap

1.  **Phase 1 (Delivered)**: Knowledge base establishment and project inventory.
2.  **Phase 2 (Completed)**: Shared-container feasibility investigation and implementation.
    -   Verified the ~2.9GB overhead claim (reduced to ~300MB per container).
    -   Implemented symlinking `common_dlls` instead of copying.
    -   Added an experimental "Shared Base" toggle in Emulation settings.
3.  **Phase 3 (Next)**: Optimization of container creation speed.
