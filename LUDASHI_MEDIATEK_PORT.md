# LUDASHI fork — MediaTek G99/G100

This branch rebrands the GameNative-derived Android launcher as **LUDASHI** and adds a first-pass MediaTek MT6789-family capability gate.

## Identity
- Launcher label: LUDASHI
- Android application ID: `app.ludashi.mobile`
- Internal Kotlin namespace remains `app.gamenative` for this migration stage. Changing every Kotlin package and all deep-link/provider integrations is a separate source-wide migration and is intentionally not faked by changing only the Gradle namespace.
- Version: 1.5.0-ludashi

## Device detection
`DeviceGate.isMediaTekG99G100()` checks the Android SoC model, hardware and board identifiers for the MT6789 family. Vendors may expose different identifiers, so detection can return false on some firmware builds. This gate only marks the family as eligible for the adaptive-FPS capability default; it does not force clocks or change thermal policy.

## Safety and performance
No app can promise zero frame drops, a fixed temperature, or the absence of thermal throttling across different games, drivers, ambient temperatures and firmware. Android thermal management remains enabled. LUDASHI must not write undocumented thermal nodes or disable vendor safety controls.

The Helio G99/G100 baseline should be measured on real devices before shipping aggressive CPU/GPU presets. Mali-G57 uses the vendor Vulkan driver; Qualcomm Turnip drivers are not a Mali driver. Recommended first validation matrix:
- 960x540 at 30 FPS as the initial stability target
- 1280x720 only when sustained frame-time and temperature tests pass
- Balanced power strategy; avoid fixed maximum CPU/GPU clocks
- Keep all scheduling and thermal safeguards under Android/vendor control
- Record frame-time, temperature/headroom and sustained FPS for at least 30 minutes per game

## Build
GitHub Actions workflow `.github/workflows/ludashi-android.yml` builds the modern Android debug APK with no signing secrets. The artifact is debug-signed and is for installation/testing, not a production release.

## Remaining engineering work
This is an initial rebrand and device-detection pass, not a claim that the entire UI, all native runtime components, every provider integration or every performance control has been rewritten. Runtime presets need device telemetry and game-by-game validation before being enabled by default.
