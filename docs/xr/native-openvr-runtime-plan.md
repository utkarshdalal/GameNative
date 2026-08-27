# GameNative native OpenVR runtime preparation

## Objective

Keep OpenComposite's mature OpenVR application ABI and compatibility logic, but replace its `DrvOpenXR` backend with a `DrvGameNative` backend. The new backend must connect to the same native XR host used by GameNative's Windows OpenXR runtime. It must not create a second Windows OpenXR session.

The target paths are:

```text
OpenXR game -> GameNative Windows OpenXR runtime ----\
                                                    -> shared XR transport/core -> native Vulkan presenter -> headset OpenXR
OpenVR game -> OpenComposite ABI -> DrvGameNative --/
```

This should make the OpenVR path comparable to the direct OpenXR path. A final native composition/blit may still be required when an application texture cannot itself be a headset-runtime swapchain image, but there must be no CPU image copy and no per-frame texture re-registration.

## Prepared repositories

- GameNative: `C:\Users\flori\Documents\Coding\gamenative`
- OpenComposite development checkout: `C:\Users\flori\Documents\Coding\OpenComposite-gamenative`
- OpenComposite upstream revision: `a27e7e6a64bdcd1eff6b7fba1ea2ea34bcf1273d`
- Baseline compatibility commit in the prepared checkout: `f0dde5f`
- Development branch: `gamenative-native-openvr`
- Upstream remote is named `upstream` and its push URL is disabled. Add a writable fork as `origin` before pushing development work.

Recreate the checkout with:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\prepare-native-openvr-dev.ps1
```

Build the current developer checkout without changing APK payloads with:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build-native-openvr-dev.ps1
```

The isolated output defaults to `app/build/native-openvr-dev/opencomposite_x64.dll`. Normal Gradle packaging continues to use the disposable, pinned `app/build/opencomposite-source` checkout.

## Correct integration seam

`OpenOVR/Drivers/Backend.h` defines `IBackend`, `ITrackedDevice`, and `IHMD`. `OCOVR/openvr_api.cpp` currently constructs `DrvOpenXR::CreateOpenXRBackend(...)`, while compositor calls are routed through `BackendManager`. Add a sibling `DrvGameNative` static library implementing `IBackend` and choose it in `VR_InitInternal2` for GameNative builds.

Do not fork or rewrite the generated OpenVR interfaces. Retain:

- OpenVR interface/version exports and generated stubs;
- application/action manifest parsing and legacy input compatibility;
- D3D11/Vulkan texture interpretation and bounds handling;
- controller/profile property emulation;
- known application-type compatibility behavior.

Do not retain in the GameNative backend:

- creation of a Windows OpenXR instance or session;
- OpenXR action sets owned by `DrvOpenXR`;
- OpenXR swapchains or compositor submission inside the Wine process;
- per-game replacement of Valve's public `openvr_api.dll` as the final deployment model.

During bring-up, DLL replacement remains an acceptable loader mechanism. The final runtime should expose a SteamVR-compatible runtime registration and provide `vrclient_x64.dll` centrally.

## Shared client/host protocol

Extend the existing GameNative XR client/Unix bridge instead of creating an unrelated socket protocol. Version every structure and reject incompatible peers explicitly.

### Control plane

Use the existing local Unix-domain control channel only for low-frequency operations:

- protocol handshake and feature negotiation;
- create/destroy client and scene session;
- register/unregister graphics device and submitted images;
- transfer external-memory and synchronization file descriptors;
- action manifest and binding setup;
- haptic requests, errors, and orderly shutdown.

### Data plane

Use shared memory for high-frequency state:

- predicted display time and frame serial;
- HMD/controller poses and validity flags;
- buttons, touches, axes, skeletal state where available;
- session visibility/focus state;
- compositor timing counters and dropped/repeated-frame statistics.

Use atomic sequence counters around snapshots. Never serialize poses or controller state through a request/response socket once the shared state page is mapped.

### Graphics plane

For each eye or array image:

1. Import the Wine/DXVK Vulkan image into the native process once using external memory.
2. Cache the registration by stable image identity, dimensions, format, array layer, and generation.
3. Transfer file descriptors only during registration/recreation.
4. Per frame, publish image id, bounds, color space, pose/time serial, and a GPU-completion primitive.
5. Wait on the GPU primitive natively, perform only the required layout/format transition or composition pass, and signal completion before reuse.

Never map image pixels on the CPU. Never allocate or import an `AHardwareBuffer` per frame. Prefer timeline semaphores when the Wine/Vulkan export path supports them; otherwise use sync FDs/fences with an explicit ownership protocol.

## Work packages

### 1. Backend selection and clean compile

- Add `GAMENATIVE_BACKEND` CMake option.
- Add `DrvGameNative` target and a small backend factory header independent of OpenXR.
- Move the direct `DrvOpenXR::CreateOpenXRBackend` call behind the factory.
- Make `VR_IsHmdPresent` query the selected backend rather than constructing a temporary OpenXR instance.
- Keep upstream `DrvOpenXR` fully buildable for comparison.

Acceptance: both stock OpenComposite and the GameNative variant compile; the latter loads and performs a versioned handshake without creating Windows OpenXR objects.

### 2. Remove hidden `XrBackend` coupling

Audit `BaseCompositor`, `BaseInput`, `BaseOverlay`, `BaseSystem`, and compositor implementations. Replace direct `XrBackend` calls with narrow `IBackend` capabilities. Important known calls include input-session restart and Vulkan physical-device lookup. Add capability methods rather than downcasts.

Acceptance: `OCCore` has no include dependency on `DrvOpenXR/XrBackend.h` in a GameNative build.

### 3. Tracking, display, and timing

- Implement HMD and controller devices from the shared state page.
- Convert GameNative stage/local spaces to OpenVR standing/seated/raw universes consistently.
- Report actual floor-level standing origin; do not derive floor height from the current HMD pose.
- Source recommended size, eye transforms, FOV, IPD, refresh period, focus, and vsync timing from the native host.
- Make `WaitGetPoses` wait for the native predicted-frame serial, not a generic sleep.

Acceptance: a scene application receives stable stereo poses and correct floor height before graphics submission.

### 4. Device-local graphics submission

- Start with D3D11/DXVK because it is the dominant GameNative path.
- Reuse the proven Wine Vulkan handle-unwrapping and Unix-bridge mechanisms.
- Register images once and keep them device-local.
- Support per-eye textures, texture arrays, bounds, sRGB/linear formats, and image recreation.
- Add Vulkan application submission after D3D11 is stable.

Acceptance: eye images appear as native stereo projection layers, never as the immersive-mode flat panel; transport logs show zero CPU image copies.

### 5. Input, haptics, and compatibility

- Map OpenVR action manifests to the native input snapshot.
- Preserve legacy controller-state APIs.
- Implement Oculus Touch-compatible properties and paths while keeping other profiles graceful.
- Forward haptics through the host control plane.
- Support scene, overlay, utility, and background application types without allowing helper clients to steal the scene session.

Acceptance: The Lab interactions work; background/utility clients do not crash or start competing sessions.

### 6. Runtime registration and lifecycle

- Package one central OpenVR runtime directory in GameNative.
- Generate `openvrpaths.vrpath`/registry-compatible discovery for the Wine prefix.
- Provide `vrclient_x64.dll` and required manifests centrally.
- Keep per-game DLL replacement only as an opt-in fallback.
- Tie scene ownership to immersive activity lifecycle and retain diagnostics after activity exit.

Acceptance: an unmodified OpenVR game discovers the runtime as SteamVR-compatible and starts through GameNative immersive mode.

### 7. Performance completion

- Move the native presenter from GLES to Vulkan so import, synchronization, and headset submission share one graphics API.
- Stop flat X-server rendering and flat-panel image copies only after native stereo frames are confirmed active; restore them on disconnect/error.
- Replace generic 2:1 repetition with compositor-aware pacing driven by predicted display time and measured application cadence.
- Profile render, Wine/FEX, transport, and XR threads separately before changing affinities/priorities.
- Update FEX and its lower-memory JIT cache independently of the OpenVR backend. Do not add Beat Saber-specific TSO metadata.

## Required diagnostics

Every launch must log:

- OpenVR application type and requested interface versions;
- selected backend and protocol versions/features;
- native host connection/session state;
- graphics API, device identity, image registration/recreation, format and dimensions;
- external-memory and synchronization path selected;
- frame serials, waits, drops, repeats, and GPU timeout/errors;
- input profile/action manifest status;
- orderly teardown reason.

Logs must be written outside ephemeral activity state so they remain exportable after a crash.

## Regression matrix

- Beat Saber direct OpenXR: unchanged behavior and performance.
- Beat Saber OpenVR mode: stereo projection, controls, audio, correct floor.
- The Lab: multiple action sets, session recreation, overlays, and controller bindings.
- Vertigo 2: stable loading/menu/gameplay without positional flicker or GPU-memory growth.
- Bigscreen: application-type handling and media/audio behavior.
- Disconnect/crash: immersive menu recovers and the next launch does not inherit stale sessions or image handles.

Record CPU/GPU frame time, image-copy count, imported-image count, memory growth, and end-to-end pose age for every comparison.

## Guardrails

- Keep the current GameNative OpenXR path working throughout development.
- Do not mix experimental device-local transport patches into the known-good branch without a feature gate.
- Do not create a second headset OpenXR session for OpenVR.
- Do not add title-specific behavior unless it represents a documented compatibility quirk with a safe default.
- Preserve GPLv3 notices and track upstream OpenComposite commits and local patches explicitly.
