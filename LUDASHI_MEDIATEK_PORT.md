# LUDASHI — MediaTek G99/G100 build

LUDASHI is a branded GameNative-derived Android launcher/runtime branch with a device profile aimed at MediaTek MT6789-family phones, including Helio G99/G100.

## App identity
- Display name: **LUDASHI**
- Android application ID: `app.ludashi.mobile`
- Kotlin namespace remains `app.gamenative` to preserve the upstream source and generated/resource references during this migration. It is not a second app identity exposed by Android's package manager.
- Version: `1.6.0-ludashi`

## Implemented in this branch
- LUDASHI launcher name, icon artwork, palette and application ID.
- MT6789-family detection using Android SoC, hardware and board identifiers.
- A Device Profile panel in Performance settings showing model, reported SoC, CPU logical cores, RAM, battery temperature, Android thermal status and ABI.
- Persistent Hemat / Seimbang / Performa modes. The selected mode supplies the strategy to the existing auto-tuner when a MediaTek game profile loads.
- Separate checks for software frame-pacing eligibility and tested hardware clock-control defaults. G99/G100 detection does not automatically switch on unverified CPU/GPU clock control.
- GitHub Actions debug APK build for the Modern Android flavor.

## What the profile modes actually control
The three modes select the existing auto-tuner strategy:
- **Hemat** → power-efficient strategy
- **Seimbang** → balanced strategy
- **Performa** → more responsive/aggressive tuning strategy

The strategy is used only when the saved game profile has auto-tuning enabled and a compatible performance driver exposes the required controls. It does not itself raise clocks, enable root access, alter DXVK/Box64 settings, or guarantee a specific frame rate.

The Performance settings panel also reads the current Android thermal status and battery temperature. Battery temperature is not the same as CPU/GPU junction temperature; use it as a basic status indicator only.

## MediaTek / Mali limitations
- Helio G99/G100 devices commonly use Mali-G57-class graphics, but actual renderer and driver capabilities vary by firmware.
- The existing PServer GPU interface is KGSL/Adreno-oriented. It must not be treated as Mali GPU clock control.
- If a device has no compatible performance driver, CPU/GPU clock control and automatic clock tuning remain unavailable. The Android scheduler and thermal manager retain control.
- Never disable thermal throttling or write undocumented thermal/sysfs nodes. No fixed temperature or stable-FPS guarantee is made.

## Suggested game starting point
For initial testing, start at **960 × 540 and 30 FPS**, then raise resolution or the frame cap only after a sustained test shows acceptable frame pacing and temperature. These are recommendations; the app does not automatically rewrite each game's resolution or frame limiter.

## Build and validation
The workflow `.github/workflows/ludashi-android.yml` builds `:app:assembleModernDebug` and uploads a debug-signed APK artifact. The artifact is for testing, not a production Play Store release. The APK must still be installed and tested on a real Helio G99/G100 device before claiming hardware-specific performance gains.

## Upstream
This branch retains the upstream GameNative-derived implementation and licensing notices. Keep the GPL-3.0 license and third-party notices when redistributing modified builds.
