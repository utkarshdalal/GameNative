# LSFG Vulkan capability detection on Android

## Goal

GameNative must decide LSFG compatibility from the Vulkan capabilities of the physical device and ICD that actually execute the game, not from GPU vendor names, driver package names, Turnip/Vortek strings, or Android build properties.

The authoritative probe therefore lives in the bundled `lsfg-vk-android` Vulkan layer/framegen backend. A Kotlin/Android-side Vulkan probe is intentionally not authoritative because it can inspect the host/system ICD while Wine/DXVK is using a different ICD or wrapper.

## Two-stage support model

1. **GameNative loader eligibility** — `LsfgVkManager.isSupported()` currently verifies the Bionic runtime path that can install and inject the Vulkan layer. This is a transport/loader prerequisite, not a GPU whitelist.
2. **Native Vulkan capability decision** — the layer selects the same physical device used by the game and evaluates its API version, extensions and features before creating the framegen logical device. A failed capability decision prevents LSFG initialization and reports the missing Vulkan requirement.

This separation deliberately avoids pretending that a pre-launch Android probe can know which container ICD will execute the workload.

## Capability model

`LSFG::Core::VulkanCapabilities` and `evaluateCapabilities()` are pure, vendor-neutral policy code. The current policy is:

| Capability | Policy |
| --- | --- |
| Vulkan API | Vulkan 1.2 or newer required |
| Android external memory | `VK_ANDROID_external_memory_android_hardware_buffer` required for the current Android frame exchange |
| AHB format class | Defined `VkFormat` is required at the actual storage-image import point. `VK_FORMAT_UNDEFINED` external-format AHBs are rejected because they are sampled-image/Y'CbCr-only and cannot satisfy LSFG storage-image writes. |
| Timeline semaphore | Required; the framegen backend uses timeline semaphores |
| Synchronization | Prefer core 1.3 synchronization2; then `VK_KHR_synchronization2`; otherwise use the audited sync2-to-classic `vkCmdPipelineBarrier` translation path on Vulkan 1.2 |
| FP16 | Optional optimization. Enable `shaderFloat16` when exposed; FP32 remains the fallback |
| Subgroups | Record `supportedStages`, `supportedOperations`, and `subgroupSize` independently. Current LSFG 3.1/3.1P shader audit found no Vulkan subgroup-instruction requirement, so required subgroup masks are currently zero. If a future shader payload introduces subgroup operations, policy can require the exact stage/operation flags without changing the probe model. |
| Present mode | Query `vkGetPhysicalDeviceSurfacePresentModesKHR`. Use the configured preference only if the actual surface supports it; otherwise preserve the game's present mode, with FIFO as the final portable fallback. |

A zero subgroup size is treated as invalid whenever subgroup requirements are non-zero, guarding known driver-reporting failures.

## AHardwareBuffer format handling

`VK_ANDROID_external_memory_android_hardware_buffer` alone does not prove that an AHB can be used as an LSFG storage image.

The framegen AHB importer calls `vkGetAndroidHardwareBufferPropertiesANDROID` and reads `VkAndroidHardwareBufferFormatPropertiesANDROID.format`:

- **Defined format** — may proceed only when the reported format matches the LSFG image format.
- **External format (`VK_FORMAT_UNDEFINED`)** — rejected for the LSFG write path with an explicit `VK_ERROR_FORMAT_NOT_SUPPORTED` reason. Vulkan external-format images are restricted to sampled-image use with matching sampler Y'CbCr conversion and are not valid storage/render targets.

The policy type exposes `AhbFormatClass` so future preflight/diagnostics can distinguish `DefinedFormat`, `ExternalFormatSampledOnly`, and unprobed/unsupported states.

## External-memory FD fallback status

The desktop backend already contains OPAQUE_FD image/semaphore transport. That is **not** treated as a drop-in Android fallback in this change. The current Android exchange is AHardwareBuffer-based; enabling an FD fallback solely because `VK_KHR_external_memory_fd` is advertised would be unsafe without proving compatible export/import handle types, ownership, synchronization and the GameNative/Wine exchange path end to end.

Until such an Android transport is implemented and tested, absence of usable AHB support is an explicit unsupported condition rather than a misleading fallback claim.

## Synchronization paths

The Android framegen code emits `VkDependencyInfo`/`VkImageMemoryBarrier2` for external acquire/release and internal compute barriers. `Utils::cmdPipelineBarrier2()` selects one of three paths:

- `CORE_1_3_SYNC2` — core Vulkan 1.3 synchronization2 feature.
- `KHR_SYNC2` — Vulkan 1.2 plus `VK_KHR_synchronization2` and its feature bit.
- `LEGACY_PIPELINE_BARRIER` — translates the used sync2 stage/access masks and barriers to classic `vkCmdPipelineBarrier` conservatively.

Device creation now requests only the feature/extension matching the selected path, so the legacy path is reachable instead of being blocked by an unconditional Vulkan 1.3 feature request.

## Shader requirements and upstream cross-reference

The implementation was checked against current `PancakeTAS/lsfg-vk` source/configuration before finalizing shader policy. FP16 is an optional accelerated path rather than a support gate. Current source inspection found no LSFG 3.1/3.1P Vulkan subgroup intrinsic requirement, so subgroup capabilities remain diagnostic fields with zero required masks for this revision.

If Lossless Scaling shader payloads change, re-audit SPIR-V/DXBC requirements before changing the masks or precision policy.

Upstream: `https://github.com/PancakeTAS/lsfg-vk`

## GameNative / ExynosTools package types

`AdrenotoolsManager` again distinguishes:

- `packageType: "icd"` — existing AdrenoTools custom ICD behavior.
- `packageType: "vulkanLayer"` — install/configure a Vulkan layer while clearing custom-ICD overrides so the Android system ICD remains selected.

This is required for Samsung/Exynos use where ExynosTools is a layer over the system Vulkan ICD, not a replacement ICD.

History: commit `00abf4af88ac6dfbd59e3b1a81ba6c0b8b044971` introduced the package-type-aware importer; `ca574dfa0299a13c1841111da419bcfe1f6e0d15` reverted that importer change shortly afterward without a detailed root-cause note. The capability branch restores the previously implemented package model in an isolated commit so it can be validated independently before merge.

## Regression matrix

Native mocked/pure-policy regression tests cover:

- Vulkan 1.1 -> reject.
- Vulkan 1.2 + no sync2 + audited classic barriers -> legacy path.
- Vulkan 1.2 + KHR sync2 -> KHR path.
- Vulkan 1.3 + core sync2 -> core path.
- Missing AHB -> reject.
- AHB external-format sampled-only + writable exchange requirement -> reject.
- Defined-format AHB + writable exchange requirement -> accept.
- Missing timeline semaphore -> reject.
- FP16 absent -> FP32 fallback.
- FP16 present -> FP16 path eligible.
- Missing exact required subgroup operation -> reject when a future policy requires it.
- `subgroupSize == 0` -> reject when subgroup use is required.

Present-mode behavior is also hardened at runtime: unsupported configured modes fall back to the game's supported mode rather than failing swapchain creation.

## Expected newly eligible device/driver classes

Subject to the capability checks above, LSFG is no longer intentionally restricted to Adreno/Turnip-style identities. Expected eligible classes include:

- Qualcomm Adreno with Turnip or another Vulkan 1.2+ ICD satisfying the requirements.
- ARM Mali/Immortalis Vulkan 1.2+ drivers satisfying AHB, timeline semaphore, storage-image and synchronization requirements.
- Samsung Xclipse Vulkan 1.2+ system ICDs satisfying the same requirements, including through a separately installed ExynosTools Vulkan layer.
- Other Android Vulkan implementations that satisfy the same capability/format constraints.

These are capability-based expectations, not claims of completed physical-device validation.

## Still unsupported

LSFG remains unsupported when any required condition fails, including:

- Vulkan API below 1.2.
- Current Android path lacks `VK_ANDROID_external_memory_android_hardware_buffer`.
- An exchanged AHB is external-format-only while the LSFG path must write it as a storage image.
- Timeline semaphores are unavailable.
- No valid synchronization path is available.
- Required storage-image formats/usages or other Vulkan operations fail at point of use.
- A future shader revision requires subgroup stages/operations not exposed by the device.

## Manual on-device validation matrix before merge

Run the same LSFG workload and capture layer capability/present logs on at least:

1. **Adreno + Turnip** — regression baseline; verify existing successful behavior, explicit layer activation, FP16/FP32 selection, and MAILBOX behavior where supported.
2. **Adreno + stock/system ICD where applicable** — verify no Turnip-name dependency.
3. **Mali/Immortalis** — verify Vulkan 1.2 legacy/KHR sync path as applicable, AHB defined-format import, timeline semaphore, and present-mode fallback.
4. **Exynos/Xclipse system ICD** — verify system ICD remains selected, capability probe passes/fails only on Vulkan facts, AHB imports report defined formats, and unsupported MAILBOX falls back cleanly.
5. **Exynos/Xclipse + ExynosTools `vulkanLayer` package** — verify `packageType=vulkanLayer` does not configure an AdrenoTools ICD, the system ICD remains active, the layer loads, and LSFG capability results are unchanged except for capabilities genuinely exposed/modified by the layer.
6. **Negative fixture/device** — Vulkan <1.2 or missing required AHB/timeline capability; verify clean rejection with a useful reason and no device-lost loop.

Do not merge solely from unit/CI results; Mali and Xclipse paths require real-device validation because AHB format/usage compatibility and vendor driver synchronization behavior are runtime properties.
