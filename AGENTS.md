# GameNative AI Agent Guidelines

## Architecture Rules

-   **New UI**: Must use Jetpack Compose and Material 3. Avoid XML layouts unless modifying legacy Winlator components.
-   **Integration Logic**: Keep game store-specific logic in `app.gamenative.service` or `app.gamenative.sync`.
-   **Dependency Injection**: Use Hilt for all new components. Avoid manual instantiation of complex managers.
-   **Legacy Bridge**: Modifications to `com.winlator` should be kept minimal and documented, as this is the primary layer for upstream compatibility.

## Coding Conventions

-   **Language**: Prefer Kotlin for all new code. Use Java only if modifying existing Java files in `com.winlator`.
-   **Formatting**: Follow the project's `.editorconfig`. Run `./gradlew lint` or `./gradlew ktlintCheck` before proposing changes.
-   **Logging**: Use `Timber` for all logging in `app.gamenative.*`. Use `android.util.Log` in `com.winlator.*` for consistency with upstream.

## Build & Test Commands

-   **Sync**: `./gradlew help` (triggers sync)
-   **Assemble**: `./gradlew assembleModernDebug`
-   **Unit Tests**: `./gradlew testModernDebugUnitTest`
-   **Lint**: `./gradlew lintModernDebug`

## Critical Files & Components

-   **Container Subsystem**: `com.winlator.container.*`, `app.gamenative.utils.ContainerUtils`. **Do not touch without explicit approval.**
-   **Main Entry**: `app.gamenative.MainActivity.kt`.
-   **Environment**: `com.winlator.xenvironment.ImageFs`, `ImageFsInstaller`.

## Dependency Management

-   Versions are managed in `gradle/libs.versions.toml`.
-   Do not add new dependencies without checking for existing alternatives in the project.

## Debugging Workflow

-   **Logcat**: Filter by `app.gamenative` or `Winlator`.
-   **Containers**: Inspect `/data/data/app.gamenative/files/imagefs/home/xuser-*`.

## Pre-Change Requirements

Before making significant changes:
1.  Explain the intended change in detail.
2.  List all affected files.
3.  Wait for user approval.
