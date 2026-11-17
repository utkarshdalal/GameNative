# LUDASHI — MediaTek G99/G100 runtime profile

LUDASHI is a GameNative-derived Android Windows-game runtime branch with an additional device profile for MediaTek MT6789-family phones, including Helio G99/G100.

## App identity
- Display name: **LUDASHI**
- Android application ID: `app.ludashi.mobile`
- Kotlin namespace remains `app.gamenative` to preserve upstream internal references during this migration.
- Version: `1.6.0-ludashi`

## Runtime integration in this branch
- The underlying GameNative runtime is retained: Wine/Proton, Box64, FEXCore, DXVK/VKD3D, container handling, Vulkan renderers and native Winlator bridge components.
- LUDASHI branding, application ID, palette and launcher artwork.
- MT6789-family detection using Android SoC, hardware and board identifiers.
- Device Profile panel in Performance settings showing Android-reported model/SoC, CPU logical core count, RAM, battery temperature, Android thermal status and ABI.
- Persistent Hemat / Seimbang / Performa selection.
- **Apply profile to this container** action in the Emulation tab. This updates the actual `ContainerData` runtime settings before the user saves the container.
- Automated tests for the profile-to-container mapping.
- GitHub Actions workflow for a Modern Android debug APK.

## Per-container profile mapping
| Mode | Resolution | Box64 preset | FEXCore preset | DXVK frame cap |
|---|---|---|---|---|
| Hemat | 854 × 480 | Stability | Stability | 30 FPS |
| Seimbang | 960 × 540 | Intermediate | Intermediate | 30 FPS |
| Performa | 960 × 540 | Performance | Performance | 30 FPS |

The helper edits only the selected container's resolution, Box64/FEXCore preset identifiers and `dxwrapperConfig.framerate`. DXVK consumes that frame-rate setting when DXVK is the selected DirectX wrapper. Other wrappers may ignore the value.

The profile deliberately does **not** force a graphics driver, Turnip/Adreno driver, CPU affinity mask, CPU/GPU clock, kernel node, root permission or thermal setting. Existing container values for those properties are preserved. The user must press the container dialog's Save action for the new values to persist.

## Auto-tuning and hardware controls
The selected Hemat / Seimbang / Performa mode also supplies the strategy to the existing auto-tuner when a MediaTek game profile loads. This strategy affects runtime clock decisions only if the container has auto-tuning enabled and the current performance driver exposes the required controls.

G99/G100 detection does not automatically validate hardware clock-control presets. The existing PServer GPU interface is KGSL/Adreno-oriented and must not be treated as Mali GPU clock control. If a device has no compatible performance driver, CPU/GPU clock control remains unavailable and Android's scheduler and thermal manager retain control.

The Device Profile reads Android thermal status and battery temperature. Battery temperature is not the same as CPU/GPU junction temperature, so it is only a basic indicator. No fixed temperature or stable-FPS guarantee is made.

## Device validation still required
- Confirm the actual SoC identifiers returned by the target firmware.
- Verify Box64 and FEXCore preset behavior on Helio G99 and G100 hardware.
- Test DXVK frame limiting with games that use DXVK; compare it with WineD3D and VKD3D separately.
- Measure frame pacing, memory pressure, battery temperature and Android thermal status during sustained play.
- Test available Mali renderers on the target firmware rather than assuming a renderer is universally fastest.

## Build and redistribution
The workflow `.github/workflows/ludashi-android.yml` builds `:app:assembleModernDebug` and uploads a debug APK artifact. This artifact is for testing, not a production Play Store release. Real-device testing is still required before claiming hardware-specific performance gains.

This branch retains upstream GameNative-derived implementation and licensing notices. Keep the GPL-3.0 license and third-party notices when redistributing modified builds.
