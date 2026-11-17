# Ludashi

**Ludashi is an Android PC-game launcher fork focused on MediaTek Helio G99/G100 phones.**

It is derived from [GameNative](https://github.com/utkarshdalal/GameNative). Existing upstream Wine/Proton, FEX, container, store, controller, compatibility and cloud-save components are retained subject to their original licenses and service requirements.

## Ludashi-specific changes

- New app display name, application ID, adaptive launcher icon and graphite/teal/orange color palette.
- MediaTek MT6789 / Helio G99 / Helio G100 build-property detection.
- Adaptive FPS control enabled by default on recognized G99/G100 devices.
- Thermal-aware FPS ceiling: 30 FPS at MODERATE, 24 FPS at SEVERE, and 20 FPS at CRITICAL or higher, using Android thermal status and the kernel cooling-device fallback.
- FPS recovery is gradual after temperatures improve. The guard changes only the game's FPS cap; it does not disable Android thermal protections, overclock, or force-write CPU/GPU kernel nodes.

## Target device

Initial target: Helio G99/G100 with Mali-G57-class graphics. Detection relies on Android build properties exposing the MT6789 SoC family signature. Some vendor firmware hides or changes these properties; automatic detection may therefore need a device-specific update.

These defaults are not a measured benchmark guarantee. Actual FPS and temperature depend on the game, Wine/Proton runtime, graphics driver, resolution, memory pressure, firmware and ambient conditions.

## Build APKs

1. Open the repository's **Actions** tab.
2. Select **Ludashi Android APK**.
3. Press **Run workflow** or push a commit to `ludashi-helio-g99-g100`.
4. Download `ludashi-legacy-apk` or `ludashi-modern-apk` after a successful build.

The workflow builds APKs using the Gradle wrapper and the configured debug signing identity for sideloading.

## Package details

- Android application ID: `com.ludashi.gamenative`
- Internal Kotlin namespace retained for source compatibility: `app.gamenative`
- Display name: `Ludashi`

The new package allows Ludashi to install separately from upstream GameNative. Existing app data and sign-in sessions are not automatically migrated.

## License and attribution

This is a modified fork of [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative). The upstream project is licensed under GPL-3.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for attribution and third-party terms. Upstream services, proprietary components and API integrations retain their original requirements.

## Thermal safety

Ludashi cannot guarantee zero FPS drops, zero heat, or the complete absence of thermal throttling. The thermal-aware FPS limiter aims to reduce sustained rendering load when Android or the kernel reports elevated thermal status. Device firmware and Android thermal management remain authoritative.
