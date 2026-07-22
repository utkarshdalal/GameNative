# GameNative Architecture

GameNative is a high-performance Windows emulation layer for Android, derived from Winlator. It enables running Windows PC games via Wine/Proton and Box64/FEXCore emulation.

## High-Level Overview

The project is divided into two main conceptual layers:
1.  **Core Emulation Layer (`com.winlator.*`)**: Inherited and extended from Winlator. Handles Linux environment setup, X server management, container (Wine prefix) lifecycle, and native bridge logic.
2.  **App & Integration Layer (`app.gamenative.*`)**: Original GameNative code. Provides a modern Jetpack Compose UI, multi-store integration (Steam, Epic, GOG, Amazon), and advanced game management features.

## Module Structure

-   `:app`: The main Android application.
    -   `src/main/java/com/winlator`: Core engine logic (Java/Kotlin mix).
    -   `src/main/java/app/gamenative`: Modern app logic (Kotlin).
    -   `src/main/cpp`: Native components including Box64, FEXCore, and various patches.
-   `:ubuntufs`: A dynamic feature module containing the base Linux rootfs (`imagefs`).

## Key Subsystems

### 1. Container Subsystem
Isolation is achieved via per-game Wine prefixes called "containers".
-   **Storage**: Located at `imagefs/home/xuser-{containerId}/`.
-   **Configuration**: Stored in a `.container` JSON file within the root directory of the container.
-   **Activation**: At launch, the app symlinks `imagefs/home/xuser` to the selected game's container directory.
-   **Lifecycle**: Managed by `ContainerManager`.

### 2. ImageFs (RootFS)
The base Linux environment is stored in `imagefs/`.
-   **Variants**: Supports `glibc` and `bionic` variants.
-   **Shared Home**: The `/home` directory is symlinked to a shared location (`imagefs_shared/home`) to persist user data across variant changes.
-   **Wine/Proton**: Stored in `/opt/`. Bundled and imported versions are supported.

### 3. Navigation & State Management
-   **UI Framework**: Jetpack Compose with Material 3.
-   **Navigation**: `navigation-compose` with Type-safe routes.
-   **DI**: Hilt for dependency injection.
-   **Database**: Room for game metadata and local state.
-   **Preferences**: Jetpack DataStore (Preferences).

### 4. Native Integration
-   **Emulation**: Box64 (x86_64 -> ARM64) and FEXCore.
-   **Graphics**: Support for Turnip (Vulkan), Zink (OpenGL over Vulkan), and DXVK/VKD3D.
-   **Audio**: PulseAudio server integration.

## Data Flow: Game Launch

1.  **Selection**: User selects a game in the UI.
2.  **Preparation**: `ContainerUtils` and `ContainerManager` ensure the container exists and is configured.
3.  **Activation**: `ContainerManager.activateContainer()` updates the `xuser` symlink.
4.  **Launch**: `MainActivity` (or `XrActivity`) starts the `XEnvironment`, initializing the X server and PulseAudio, then executing Wine via the selected emulator.
