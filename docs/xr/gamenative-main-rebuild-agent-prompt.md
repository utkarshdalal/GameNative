# Agent handoff: rebuild GameNativeVR on official GameNative

> This document is a self-contained implementation prompt. Give the complete file to the agent performing the migration.

## Mission

Build the GameNativeVR pipeline on top of the current official GameNative `master`, using GameNative's official `modernXr` immersive activity as the Android and Quest foundation.

Do **not** create a standalone VR activity alongside the official one. Recreate the hardware-validated Windows OpenXR/Wine/AHardwareBuffer behavior specified below, terminating it inside the official immersive activity's single OpenXR session. The result must retain GameNative's normal container, launch, renderer, quick-menu, and lifecycle behavior while adding native PC-VR stereo presentation.

The primary objective is the lowest-effort feature-complete migration that does not preserve architectural dead ends. Optimize only after the functional pipeline and fallback behavior are measurable.

## Fresh-start assumptions and source of truth

Assume a completely fresh environment with no previous conversation, no local migration branch, and no access to any earlier GameNativeVR implementation. Everything required to rebuild the feature is specified in this prompt.

- The only source repository is `https://github.com/utkarshdalal/GameNative.git`.
- Start from the latest official `master` and record its exact SHA before editing.
- Official master was audited at `7213561c34b9619e5a0cd36b34362e94f0e735d7` on 2026-08-19. This is context, not a commit to reset to.
- The official immersive implementation originated in `https://github.com/utkarshdalal/GameNative/pull/1831`.
- FEX `FEX-2608`, published 2026-08-05, was current during the audit.

Do not add another remote, fetch a fork, search for an old GameNativeVR branch, or assume prebuilt GameNativeVR source is available. Work from the checked-out current official `master` and implement the specification below using that codebase as the sole foundation. If the execution environment automatically requires a task branch for safe editing, it may create one from the recorded `master` SHA, but it must not import code from any other branch or repository.

## Non-negotiable architecture

There must be exactly **one Quest OpenXR session** and it must be the one owned by the official immersive activity/native module.

```text
Windows game
  ├─ Native OpenXR ───────────────┐
  └─ OpenVR → OpenComposite ──────┤
                                  v
Windows GameNative OpenXR runtime DLL
  ├─ control/timing/input protocol
  └─ Wine unixlib + AHardwareBuffer frame transport
                                  v
WindowsVrRuntimeService in official ImmersiveXrActivity
                                  v
official XrImmersiveSession (the only Quest OpenXR session)
  ├─ stereo projection mode
  ├─ flat fallback quad
  ├─ official quick menu and passthrough
  └─ official controller/lifecycle/performance handling
                                  v
Quest compositor
```

Do not introduce another VR activity, create a second native Quest session, or layer a custom activity over `ImmersiveXrActivity`. Two competing sessions would create severe lifecycle, focus, timing, input, and performance problems.

## What currently works and should be preserved

### Proven on hardware

The following path has produced a visible and playable Beat Saber image on Quest:

```text
Windows OpenXR loader
  → GameNative Windows OpenXR runtime
  → D3D11/DXVK swapchains
  → Wine unixlib
  → AHardwareBuffer stereo transport
  → Quest native projection layers
```

Preserve the behavior and data contracts behind:

- Windows runtime negotiation, instance/session lifecycle, spaces, views, actions, haptics, and projection-layer parsing.
- D3D11/DXVK swapchain handling and the ARM64EC Wine bridge.
- AHardwareBuffer handle transfer over the local abstract Unix socket.
- Control-plane tracking, input, timing, frame state, and diagnostics.
- Runtime manifest plus 32-bit/64-bit registry/environment configuration.
- Per-game OpenComposite installation with backup and restoration.
- Effective container/emulator/graphics launch logging.
- Suspending redundant flat presentation after stereo becomes healthy.

Implement these as new, `modernXr`-scoped modules in the official tree:

- a Windows OpenXR runtime DLL for x64, with x86 support kept as a later compatibility target
- a fixed-width Wine unix-call ABI shared by the PE runtime and Wine unixlib
- a Wine unixlib plus ARM64EC/ARM64X builtin companion for Vulkan/DXVK interop
- an Android control-plane service
- a native AHardwareBuffer frame receiver integrated into `XrImmersiveSession`
- a crash-safe payload/runtime-manifest manager
- an optional per-game OpenComposite installer and restorer

Use a clear new-source layout rather than mixing the implementation into the large Compose screen. Adapt names only if current official conventions require it:

```text
app/src/main/java/app/gamenative/ui/screen/xr/windows/
  WindowsVrRuntimeService.kt
  WindowsVrRuntimeConfig.kt
  WindowsVrPayloadManager.kt
  WindowsVrControlServer.kt
  WindowsVrDiagnostics.kt

app/src/main/cpp/xrimmersive/
  xr_immersive.cpp/.h                 # existing official session, extended
  xr_windows_projection.cpp/.h        # projection swapchains/layer submission
  xr_windows_transport.cpp/.h         # AHB/dma-buf receive and lifetime
  xr_windows_protocol.h               # shared bounded native structs/constants

app/src/main/windows/openxr_runtime/
  gamenative_openxr_runtime.c
  gamenative_openxr_runtime_x64.def
  gamenative_openxr_runtime_x86.def   # later milestone
  gamenative_openxr_unix_abi.h
  unix/gamenative_openxr_unix.c
  builtin/gamenative_xr_unixbridge.c

app/build/generated/xrPayload/modernXr/ # generated artifacts only
```

Register the generated payload directory only with the `modernXr` source set in `app/build.gradle.kts`; the audited build script explicitly defines `modernXr` asset directories, so merely creating `src/modernXr/assets` is not sufficient. Add reproducible build tasks/scripts for the Windows PE runtime, Wine unixlib/builtin companions, and Android native module. Pin compiler inputs and the Khronos OpenXR-Headers version/checksum. It is acceptable for a build task to download the pinned Khronos headers; do not copy an unexplained header snapshot or binary from another repository. Generated artifacts must be traceable to source and fail validation when stale or wrong-architecture.

### Partially working, retain behind an experimental compatibility boundary

- OpenComposite successfully initializes for some OpenVR titles and can establish OpenXR sessions, actions, and D3D11 bindings.
- The Lab reached the runtime, established sessions, and selected Quest Touch profiles, but did not produce a verified image.
- Bigscreen initialized OpenComposite but did not become a usable session and has crashed in its OpenVR startup path.

Therefore OpenVR/OpenComposite is **not** a proven general compatibility path. Retain it as an adapter and diagnostic target, but do not describe it as complete or make it block native OpenXR parity.

### Compiled or implemented but not sufficiently validated

- Vulkan-client and D3D12 paths.
- 32-bit Windows runtime path.
- dma-buf/modifier transport on Quest; tested devices reported dma-buf import unavailable.
- Generic compatibility across non-Unity engines.

Keep these code paths only where they remain maintainable and covered by build/smoke tests. Label them accurately in UI and logs.

## What to discard from the new implementation

“Discard” means do not recreate these legacy architectural paths in the fresh implementation.

| Rejected path or behavior | Disposition | Reason |
|---|---|---|
| A second custom VR activity and native Quest session | Do not create | Conflicts with the official immersive activity and creates a second OpenXR owner. |
| Separate VR launchers or detours in `MainActivity`/`PluviaMain` | Do not create | Official `MainViewModel` already selects `ImmersiveXrActivity`. |
| A second custom X-server surface/view registry | Do not create | Official DirectGL/DirectVulkan bridges own flat presentation. Implement presentation suspension through their official hooks. |
| A hand-rendered menu `SurfaceTexture` or separate native menu renderer | Do not create | The official immersive quick menu is the correct UI/lifecycle owner. |
| Duplicate XR copies of normal container settings | Do not create | Official container persistence is the source of truth. Duplicated preferences create stale settings. |
| Launch-argument sanitizing heuristics | Do not create | They can delete legitimate game arguments. Use the official `LaunchInfo` and Steam launch selection unchanged. |
| Broad graphics-driver/wrapper forcing | Do not create | Respect the selected container configuration. Validate capability and report an actionable error instead. |
| Vendored OpenXR headers and a private Android loader setup | Do not create | Official `modernXr` already uses Khronos Prefab/OpenXR loader dependencies. |
| Patched Wine 9.2 winevulkan binaries/scripts | Do not create | Use GameNative-supported Proton/Wine ARM64EC paths and the Wine builtin companion described below. |
| Synthetic/test producer in production packaging | Move to debug/test only | Useful for transport testing, unnecessary in release. |
| Separate native theater and clock implementations | Do not create as core features | Official flat quad, passthrough, and quick menu cover the core use case. Add optional overlays later through the official session. |
| A second monolithic native Quest frame loop | Do not create | Extend the official session with small projection, transport, and timing components. |
| Beat Saber-specific TSO metadata | Do not implement | The product is generic and disabling TSO is correctness-sensitive. |

Do not remove or rewrite unrelated official GameNative behavior to make the migration easier.

## Official code that must remain the foundation

The current official implementation is centered on:

- `app/src/main/java/app/gamenative/ui/screen/xr/ImmersiveXrActivity.kt`
- `app/src/main/java/app/gamenative/ui/screen/xr/ImmersiveSessionHooks.kt`
- `app/src/main/java/app/gamenative/ui/screen/xr/XrNative.kt`
- `app/src/main/java/app/gamenative/ui/screen/xr/DirectGLBridge.kt`
- `app/src/main/java/app/gamenative/ui/screen/xr/DirectVulkanBridge.kt`
- `app/src/main/cpp/xrimmersive/xr_immersive.cpp`
- `app/src/main/cpp/xrimmersive/xr_immersive.h`

The JNI entry points are currently implemented alongside the Kotlin declarations in `XrNative.kt` and the native immersive source rather than in a separate `xr_immersive_jni.cpp` file.

It already provides:

- One lifecycle-correct Quest OpenXR session.
- A flat 1280×720 quad layer.
- Direct GL and Vulkan AHardwareBuffer sharing for flat content, with PixelCopy fallback.
- Passthrough, Xbox-style Touch-controller input, official quick menu integration, and activity lifecycle handling.
- Sustained-high Quest CPU/GPU performance requests, a render-thread hint, and a 72 Hz display request.

Extend this implementation; do not replace it.

`XServerScreen.kt` is already close to Android's DEX/register limit. Preserve `ImmersiveSessionHooks` as a single parameter. Add capabilities through one cohesive hook/service object rather than adding many composable parameters or lambdas.

## Self-contained implementation specification

The following contracts replace any dependency on an older implementation. Names may be adapted to official conventions, but behavior, ownership, validation, and lifecycle boundaries must be preserved.

### Windows OpenXR runtime

Create a Windows OpenXR runtime DLL that exports `xrNegotiateLoaderRuntimeInterface` and `xrGetInstanceProcAddr`, negotiates OpenXR loader/runtime interface version correctly, and initially advertises OpenXR 1.0 plus `XR_KHR_D3D11_enable`. Build x64 first. Keep the source architecture-neutral enough to add x86 after x64 is hardware-validated. Vulkan and D3D12 extensions are later milestones, not prerequisites for the first working path.

The first complete runtime must implement:

- instance/system discovery and properties
- stereo view configuration and environment blend modes
- session create/destroy/begin/end/request-exit plus ordered session-state events
- local, stage, view, and action spaces
- frame wait/begin/end and view location
- swapchain create/destroy/enumerate/acquire/wait/release
- D3D11 graphics requirements
- paths and action sets/actions/bindings/synchronization
- boolean, float, vector2, and pose action state
- bound-source enumeration/localized names
- controller haptics

Validate OpenXR structure types, array capacities, handle ownership, session call order, swapchain image state, sub-image rectangles, and projection-layer view counts. Unsupported extension functions must return `XR_ERROR_FUNCTION_UNSUPPORTED`; optional debug-utils requests must not abort initialization.

For D3D11 under DXVK, obtain the underlying Vulkan physical device, logical device, queue, queue-family index, and submission synchronization through DXVK's native interop interface. Flush and lock the DXVK submission queue while scheduling transport copies, then unlock promptly. If native interop is unavailable, return a clear OpenXR graphics-device error and diagnostics instead of silently advertising a broken path.

The runtime is a proxy: Windows swapchains are application-facing images, while submitted projection metadata and completed image contents are sent to the Quest endpoint. Parse every `XrCompositionLayerProjection` in `xrEndFrame`, preserve each view's swapchain/image index, array index, crop rectangle, orientation, position, and FOV, and submit left and right as one frame identity.

### Control-plane protocol

Implement a versioned, localhost-only ASCII request/response server at `127.0.0.1:38476`. Bind explicitly to loopback, set `TCP_NODELAY`, cap line length, cap concurrent clients, validate every token, and terminate malformed clients without affecting the activity. Protocol version 2 uses one command per line and one response per line. Floating-point values are signed integer micro-units (`float * 1_000_000`) so the Windows runtime may remain CRT-light.

Required commands:

| Command | Required response/behavior |
|---|---|
| `HELLO` | `OK GameNativeVR 2` |
| `GET_SYSTEM` | stable system id, vendor id, and a generic Meta Quest/GameNative name |
| `GET_VIEWS` | `OK count=2 width=<recommendedWidth> height=<recommendedHeight>` from the Quest view configuration |
| `GET_BOUNDS` | stage availability and width/height in micro-units |
| `BEGIN_SESSION`, `END_SESSION`, `REQUEST_EXIT` | validate/update the proxy session and forward exit requests |
| `WAIT_FRAME` | compositor-derived predicted display time, period, `shouldRender`, and session state; block on the next Quest frame signal rather than returning unbounded |
| `BEGIN_FRAME`, `END_FRAME layers=<n>` | validate order and record first-frame progress |
| `LOCATE_VIEWS` | two poses/FOVs with validity flags |
| `GET_INPUT hand=<0|1>` | active flag, button mask, analog values, grip pose, and aim pose |
| `HAPTIC hand=<n> amp=<micro> dur=<ns> freq=<micro>` | enqueue haptics on the Quest session |
| `GFX_API <d3d11|d3d12|vulkan>` | record the active binding; unsupported bindings fail explicitly |
| `SWAPCHAIN_CREATE ...`, `SWAPCHAIN_DESTROY`, `SWAPCHAIN_RESET` | diagnostic/accounting events with validated dimensions/counts |
| `STATUS`, `BYE` | bounded status snapshot and clean disconnect |

`LOCATE_VIEWS` carries, per eye, quaternion `qx qy qz qw`, position `px py pz`, and FOV angles `fl fr fu fd`. `GET_INPUT` carries trigger, squeeze, stick x/y, grip quaternion/position, and aim quaternion/position. Keep tracking/input snapshots behind small locks or atomically swapped immutable structs; never update Compose state for each request.

The Quest native frame loop is the timing authority. Publish its `xrWaitFrame` result and view/controller locations to the control service once per compositor frame. The Windows runtime must not create an independent timer.

### Wine unix-call ABI

Create a packed, fixed-width ABI containing no native pointer-sized fields. Use `uint32`, `int32`, `uint64`, and `int64`; 32-bit clients zero-extend addresses into 64-bit fields. Include an explicit ABI version and reject mismatches.

Required calls:

1. initialize/version handshake
2. set Vulkan context: client physical device, device, queue, queue family/index, and whether handles are already host handles
3. create/destroy proxy swapchain
4. acquire/reuse an image slot with timeout
5. submit one image/view
6. submit a stereo pair atomically

Support at most a small bounded number of swapchains and images (for example 32 swapchains and four images each). The stereo submission contains at most two views and includes slot, image index, eye, array index, crop rectangle, pose, and FOV in micro-units. Return explicit argument, unavailable, Vulkan, transport, and timeout errors.

For ARM64EC Wine, package the unixlib as an aarch64 ELF plus a Wine-builtin ARM64X PE companion with correct machine type, 64 KiB section/file alignment, and Wine builtin signature. Also define where an x86 builtin companion will live when x86 support is enabled. Do not patch or replace Wine's generic `winevulkan` module.

### Frame data-plane protocol

Use a Linux abstract `AF_UNIX` `SOCK_STREAM` endpoint named `@gamenative-xr`; a filesystem `/tmp` socket inside proot is not reliably the same namespace as the Android process. Start the listener before Wine. Use bounded registration tables: two eyes, no more than 128 registered image slots per eye, and a latest-complete-frame exchange rather than an unbounded queue.

Protocol messages are newline-delimited ASCII followed, where specified, by an ancillary handle/fd transfer:

| Message | Semantics |
|---|---|
| `HELLO version=2` | negotiate protocol before any registration |
| `BUFFER eye=<0|1> index=<n> w=<n> h=<n> swizzle=<0|1>` | consumer replies `OK`, producer sends an AHardwareBuffer handle, consumer replies `OK stored` |
| `DMABUF eye=<n> index=<n> w=<n> h=<n> planes=<1..4> fourcc=<n> modifier=<n> strideN=<n> offsetN=<n>` | optional fallback; transfer one `SCM_RIGHTS` fd per plane after `OK` |
| `FRAME frame=<monotonicId> eye=<n> index=<n> fence=<0|1> x=<n> y=<n> w=<n> h=<n> projection=1 qx=... fd=...` | present one registered image plus crop and projection metadata; transfer acquire sync-fd when `fence=1` |
| `ACQUIRE eye=<n> index=<n> timeout=<ms>` | wait until the consumer releases the slot; return `OK fence=0` or `OK fence=1` followed by release sync-fd |
| `BYE` | cleanly disconnect without stopping the listener |

The abbreviated `FRAME` projection fields are `qx qy qz qw px py pz fl fr fu fd`, all in micro-units. Validate crop bounds and all indices before claiming a frame. A line reader must stop exactly at newline and must not consume the one-byte payload used by the following ancillary transfer.

On registration, receive the AHardwareBuffer with Android NDK handle APIs and retain it for the slot lifetime. Import it into the Quest render device only on the Quest render thread. On each `FRAME`, wait/import the acquire fence, sample/copy it into the Quest OpenXR projection swapchain, create a release fence after GPU consumption is queued, and make that fence available to `ACQUIRE`. Close every fd exactly once. Release or immediately acknowledge superseded, never-sampled frames so a fast producer cannot fill the ring and deadlock.

Pair eyes by the monotonic `frame` id. Submit only a complete pair; never combine a new left eye with an unrelated right eye. If one eye is late, retain the previous complete pair for bounded compositor reuse. Buffer registration is persistent; do not resend hardware handles every frame.

AHardwareBuffer is the required first path. dma-buf/modifiers are optional fallback because Quest devices may not expose usable dma-buf import. Log the selected path once, not every frame.

### Initial producer copy path

For the first reliable D3D11 implementation, allocate one importable transport AHardwareBuffer per eye/image slot using `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` with GPU sampled and color-output usage. Import it into the producer's Vulkan device with `VK_ANDROID_external_memory_android_hardware_buffer`. At submission:

1. transition the game/DXVK source image to transfer source
2. transition the AHardwareBuffer-backed image to transfer destination
3. `vkCmdCopyImage` the requested subresource/rectangle
4. restore required layouts
5. submit on the synchronized DXVK queue
6. export a sync-fd acquire fence when supported
7. send the corresponding `FRAME`

Handle BGRA-to-RGBA channel order through an explicit swizzle flag. Reuse command buffers, images, memory, and synchronization objects; do not allocate them per frame. This copy path is the known-safe baseline and must remain as fallback when the later direct-render optimization is attempted.

### Payload and prefix layout

Package generated artifacts inside the `modernXr` APK assets and install them idempotently into each selected prefix:

```text
C:\gamenative\xr\gamenative_openxr64.dll
C:\gamenative\xr\gamenative_openxr32.dll          # only when x86 is supported
C:\gamenative\xr\gamenative_openxr.json
C:\gamenative\xr\gamenative_openxr64.json
C:\gamenative\xr\gamenative_openxr32.json
C:\windows\system32\gamenative_openxr.dll        # x64/common redirected path
C:\windows\syswow64\gamenative_openxr.dll        # x86 redirected path
<wine>/lib/wine/aarch64-unix/gamenative_xr_unixbridge.so
<wine>/lib/wine/aarch64-windows/gamenative_xr_unixbridge.dll
C:\windows\system32\gamenative_xr_unixbridge.dll
```

Use architecture-correct equivalents for non-ARM64EC Wine. Validate PE machine type, ELF magic, builtin signature, and ARM64X alignment before advertising the runtime.

Generate OpenXR runtime JSON with `file_format_version: "1.0.0"`, runtime name `GameNativeVR`, and the correct Windows library path. Set both Wine registry locations:

```text
HKLM/Software/Khronos/OpenXR/1/ActiveRuntime
HKLM/Software/Wow6432Node/Khronos/OpenXR/1/ActiveRuntime
```

Also set `XR_RUNTIME_JSON=C:\gamenative\xr\gamenative_openxr.json`. Configure:

```text
GAMENATIVE_XR=1
GAMENATIVE_XR_SOCKET=@gamenative-xr
GAMENATIVE_XR_BRIDGE_HOST=127.0.0.1
GAMENATIVE_XR_BRIDGE_PORT=38476
GAMENATIVE_XR_RUNTIME_DIR=<app-private runtime directory visible to Wine>
```

Add logging variables only in diagnostic mode. Merge the required builtin override for `gamenative_xr_unixbridge=b` with the user's existing `WINEDLLOVERRIDES`; do not erase existing entries. The desktop Steam overlay may be disabled specifically for immersive Windows VR because it is not visible in the projection layer and adds hooks/memory, but log that effective override.

For OpenVR compatibility, find `openvr_api.dll` files under the launched game only, verify architecture, save an adjacent uniquely named backup, atomically install the matching OpenComposite DLL, and write minimal `opencomposite.ini` settings. On disable or recovery, restore the original and remove only files marked as GameNative-owned. Never recursively modify unrelated Steam libraries.

Obtain OpenComposite independently from its upstream project during implementation. Pin the selected commit/release and checksum in the build/payload metadata, package the x64 artifact in `modernXr`, and add x86 only with the x86 runtime milestone. Do not depend on an unversioned runtime download URL for normal launches. OpenComposite remains a replaceable compatibility adapter; no OpenComposite source is assumed to exist in the fresh clone.

## Target state model

Add an explicit presentation state owned by `XrImmersiveSession`:

```text
STARTING
  → FLAT_FALLBACK
  → STEREO_ACTIVE
  → STEREO_DEGRADED
  → FLAT_FALLBACK
  → STOPPING
```

- `STARTING`: activity/session is being created; transport listeners may start, but no game frame is assumed.
- `FLAT_FALLBACK`: submit the official flat quad and keep its normal DirectGL/DirectVulkan behavior.
- `STEREO_ACTIVE`: submit an `XrCompositionLayerProjection` with the latest complete left/right pair. Suspend redundant flat presentation.
- `STEREO_DEGRADED`: retain the latest safe stereo pair for a short bounded grace period while waiting for transport recovery.
- Return to `FLAT_FALLBACK` only after persistent loss, session teardown, or a fatal stereo compatibility error.
- `STOPPING`: stop listeners, restore payload replacements, and tear down in official lifecycle order.

Never switch modes on a single late frame. Use consecutive success/miss thresholds and log each transition exactly once.

## Canonical settings and launch behavior

The normal GameNative container remains authoritative for:

- Graphics driver and driver configuration.
- DX wrapper and DXVK/VKD3D configuration.
- Wine/Proton version.
- FEX/Box64/Box86 version and preset.
- CPU lists, WoW64 mode, Steam mode, environment variables, screen size, and launch arguments.

Add only genuinely XR-specific settings to the official container model, for example:

- `immersiveWindowsVrEnabled`
- `openVrCompatibilityMode`: `AUTO`, `ON`, or `OFF`
- `xrRenderScale`
- `xrPacingMode`: `AUTO`, `NATIVE`, or explicit `HALF_RATE`
- optional diagnostic logging level

Do not write an entire stale `Container` snapshot from the VR UI. Update only the requested XR field through the official repository/manager and reload the canonical container before the next launch.

Do not invent a second argument store. The final Windows command, `ColdClientLoader.ini`, and process snapshot must show the same executable and arguments selected by the official launch flow.

## Implementation path

### Phase 0 — Establish a clean official-master baseline and evidence ledger

1. Clone `https://github.com/utkarshdalal/GameNative.git` if it is not already the fresh checkout.
2. Check out `master`, fast-forward it from `origin/master`, and record `git rev-parse HEAD`. Continue from that tree. Only create a task branch if the execution environment requires one for safe editing.
3. Do not add a fork/reference remote or import patches from another GameNativeVR implementation.
4. Run the existing official tests and a `modernXr` build before editing; record any pre-existing failures.
5. Inspect the official immersive files listed above and confirm how the current master wires `ImmersiveXrActivity`, `ImmersiveSessionHooks`, renderer bridges, quick menu, and container launch lifecycle.
6. Create a compact compatibility matrix with columns for API (`OpenXR`, `OpenVR`), graphics (`D3D11`, `D3D12`, `Vulkan`), architecture, runtime connection, session, first stereo pair, visible image, input, and teardown.

### Phase 1 — Add a single Windows-VR integration service and minimal hooks

Create a modernXr-scoped service, with names similar to:

- `WindowsVrRuntimeService.kt`
- `WindowsVrRuntimeConfig.kt`
- `WindowsVrPayloadManager.kt`
- `WindowsVrDiagnostics.kt`

The service owns:

- Runtime payload preparation and restoration.
- Runtime manifest/registry/environment configuration.
- Control-server lifecycle.
- Frame-transport lifecycle coordination.
- Status/diagnostic snapshots.
- Presentation-state requests to the existing `XrImmersiveSession`.

Extend `ImmersiveSessionHooks` with one nested object/interface, not a collection of new parameters. Provide lifecycle points equivalent to:

```kotlin
interface WindowsVrLaunchHooks {
    fun beforeWineSystemSetup(...)
    fun afterContainerEnvironmentMerged(env: EnvVars, ...)
    fun beforeGuestProcessStart(...)
    fun onEnvironmentStarted(...)
    fun onRendererAvailable(...)
    fun onTeardown()
}
```

Adapt the signatures to official types. The important ordering rule is that critical XR environment variables must be applied **after** normal container environment variables are merged and **before** the guest process starts. Do not let an old/stale XR snapshot overwrite the canonical container.

Keep changes inside `XServerScreen.kt` small and structural. Move logic into the service/helper classes to avoid another verifier/register failure.

Acceptance criteria:

- Non-immersive and non-`modernXr` builds behave exactly as before.
- Official flat immersive mode still launches, renders, opens its quick menu, and tears down normally with Windows VR disabled.
- One service instance is created and closed per immersive game launch, with no receiver/thread/socket leaks.

### Phase 2 — Implement payload staging without policy overrides

Implement `WindowsVrPayloadManager` and runtime configuration from the payload/prefix specification above:

- Build/package x64 and x86 Windows runtime DLLs.
- Package the Wine unixlib and ARM64EC/PE bridge companions.
- Generate the active-runtime JSON manifests.
- Set `XR_RUNTIME_JSON` and 32-/64-bit active-runtime registry keys inside the selected prefix.
- Configure localhost control endpoint and abstract Unix transport endpoint.
- Install OpenComposite only when requested/auto-detected, preserving and restoring the original per-game `openvr_api.dll` atomically.
- Make preparation idempotent and recovery-safe after a killed activity.

Do not add:

- Wine 9.2 patch assets.
- hard-coded Beat Saber paths or Player.log names.
- forced wrapper changes.
- launch-argument filtering.

Generalize diagnostics to discover logs from the launched executable/game directory. A missing game-specific log must not be labeled as “Beat Saber Unity Player log unavailable.”

Add a payload version/schema marker so stale installed files are refreshed only when their content changes. Log file size, machine type, and hash in diagnostic mode without hashing large files every frame or launch-stage callback.

Acceptance criteria:

- A native OpenXR probe inside the prefix loads the GameNative runtime for both supported registry views.
- Repeated prepare/restore cycles do not lose original game DLLs.
- Selected graphics, wrapper, Wine, emulator, CPU, and launch arguments match the canonical container in effective-launch logs.

### Phase 3 — Fold stereo presentation into the official native session

Refactor the official native module into small internal units if necessary:

- flat quad presenter (existing behavior)
- projection presenter
- imported-frame transport/queue
- input/tracking bridge
- timing/state coordinator

Do not create another session creation or lifecycle loop. Implement these focused components inside the official native module:

- AHardwareBuffer import and texture creation.
- left/right frame pairing and safe replacement.
- projection-view/sub-image construction.
- pose/FOV conversion where still applicable.
- bounded transport recovery logic.

The official `XrImmersiveSession` continues to own:

- `XrInstance`, `XrSession`, reference spaces, and frame loop.
- Quest swapchains.
- `xrWaitFrame`/`xrBeginFrame`/`xrEndFrame`.
- activity focus/session-state handling.
- flat fallback, quick menu, passthrough, and controller ownership.

Add stereo color swapchains to this same session and submit the projection layer only when a complete, valid pair exists. Preserve the official flat quad as startup and compatibility fallback.

Use the official Khronos Prefab dependency and loader. Remove any need to copy the vendored OpenXR header tree into the new branch. Make `libxrimmersive.so` reproducibly buildable from Gradle/CMake or add a verified generation task; do not rely on an unexplained checked-in binary.

Acceptance criteria:

- Native debug producer can drive projection mode without Wine in a debug build.
- Only one `xrCreateSession` occurs on the Quest side.
- Opening/closing the quick menu and pausing/resuming the activity do not create another session.
- Loss of producer falls back to the official flat panel without restarting the activity.

### Phase 4 — Implement the Windows control plane

Implement the localhost TCP protocol defined above inside the modernXr-scoped runtime service. Keep protocol versioning and validate all lengths/counts before allocation or JNI calls.

The control plane must supply:

- predicted display time/period
- head and eye poses/FOVs
- session state and `shouldRender`
- controller poses, buttons, axes, and interaction profile
- haptic requests
- frame acknowledgements and health information

The data plane remains the abstract Unix socket carrying AHardwareBuffer handles and synchronization metadata. Do not combine both protocols merely for architectural neatness. Consider unification only after profiling shows a meaningful cost.

Connect the service and native session through one bounded callback/status interface. Avoid high-frequency Compose state writes and avoid per-frame logcat/file logging.

Acceptance criteria:

- Windows `xrWaitFrame` timing is derived from the actual Quest frame loop.
- Tracking and input continue while flat fallback is visible.
- Malformed or disconnected clients cannot crash the activity.
- Teardown closes server sockets promptly and allows the next launch to bind immediately.

### Phase 5 — Implement the frame data plane and prove native OpenXR/D3D11 first

Implement the Wine unixlib, fixed-width ABI, builtin bridge companions, and AHardwareBuffer transport exactly from the contracts above. Integrate their build and packaging with the Proton/Wine versions supported by official GameNative.

Bring up paths in this order:

1. x64 native OpenXR + D3D11/DXVK
2. input and haptics
3. activity pause/resume and clean teardown
4. 32-bit runtime if a real test application requires it
5. Vulkan-client and D3D12 only after the first path is stable

Do not claim a path based only on successful compilation or `xrCreateSession`. Require first valid stereo pair and a visible image.

Maintain a small queue or latest-frame exchange rather than an unbounded frame queue. Hold a frame's hardware buffer until the compositor has finished the consuming operation, then release it deterministically.

Acceptance criteria for the first milestone:

- A native OpenXR D3D11 title reaches `STEREO_ACTIVE` and produces a visible image.
- The log records runtime connection, graphics binding, swapchain dimensions/formats, first complete pair, first projection submission, and stable mode transition.
- No per-frame allocations occur in the steady-state Android/native transport loop beyond driver/runtime behavior.

### Phase 6 — Stop redundant flat presentation safely

Keep this optimization in the initial integration.

Official DirectGL already renders into its shared target efficiently, but the Vulkan immersive path can still forward content to the flat Vulkan compositor. Once the first valid stereo pair has been submitted:

- suspend only flat **presentation/scanout** work
- keep X server state, window updates, input, and any content required for fallback alive
- stop PixelCopy/fallback capture timers
- prevent DirectVulkan from submitting the redundant flat target
- resume only after persistent stereo loss or explicit return to flat mode

Implement this through official renderer/session APIs and one session-owned presentation gate. Make suspension idempotent and visible in diagnostics.

Acceptance criteria:

- A trace shows that flat presentation callbacks/submissions cease in `STEREO_ACTIVE`.
- Returning to `FLAT_FALLBACK` restores the flat image without restarting Wine.
- Menu rendering remains independent of flat game presentation.

### Phase 7 — Integrate settings into the official immersive quick menu

Add a Windows VR section/tab to the existing quick menu; do not create a separate hand-attached custom renderer.

Show:

- presentation state and connection health
- selected runtime path (`Native OpenXR` or `OpenVR compatibility`)
- render scale and pacing mode
- restart-required labels for settings that affect Wine/runtime creation
- a compact diagnostics/export action

Runtime-safe changes may apply immediately. Wine, graphics-wrapper, emulator, runtime-installation, and most swapchain changes must be clearly marked for next launch.

Do not keep the flat settings screen alive or mirror it into another texture solely to provide VR controls.

### Phase 8 — Restore OpenVR through OpenComposite as an adapter

After native OpenXR/D3D11 is stable, reconnect per-game OpenComposite:

```text
OpenVR game → OpenComposite → GameNative Windows OpenXR runtime → same transport/session
```

OpenComposite must not create a second Quest endpoint. It is only an in-prefix API translator.

Test startup, action manifests, controller bindings, session recreation on graphics-API selection, first stereo pair, and teardown. Preserve detailed OpenComposite logs, but rate-limit repetitive unmapped-binding warnings in GameNative's own logs.

Do not add title-specific compatibility hacks during the first migration. Record failures by missing API/extension/format so fixes remain engine/runtime-general.

Acceptance criteria:

- At least one OpenVR title reaches a visible projection image.
- Native OpenXR continues to work with OpenComposite disabled.
- Original game DLL restoration survives normal exit, crash, and forced-stop recovery.

### Phase 9 — Diagnostics and regression protection

Replace game-specific diagnostics with a generic staged report:

- canonical saved and loaded container settings
- effective launch configuration
- payload files, machine types, and versions
- runtime JSON and active registry values
- final executable, working directory, and exact arguments
- control/data connection state
- Quest and Windows session state
- graphics API, formats, sizes, and image counts
- first-frame milestones and presentation mode
- transport/import/copy path selected
- process snapshot and discovered game/runtime logs

Use an in-memory ring buffer and flush on failure, explicit export, or teardown. Do not run full filesystem/process scans on the render path.

Add tests for:

- protocol encode/decode and malformed lengths
- format mapping and swapchain subrect validation
- left/right pairing and buffer lifetime
- state transition success/miss thresholds
- payload backup/restore and crash recovery
- container settings round-trip without stale overwrites
- launch-argument preservation
- native build/ABI symbols for x64, x86, ARM64EC, and Android arm64 where supported

## Performance work: keep, defer, or discard

### 1. Stop flat X-server rendering after stereo transport becomes active — keep now

Implement as Phase 6. The goal is not to shut down the X server; it is to remove redundant flat presentation, capture, and compositor work while retaining a fast fallback.

### 2. Eliminate the remaining AHardwareBuffer image copy — keep as post-parity R&D

The safe baseline specified in “Initial producer copy path” imports a transport AHardwareBuffer as a Vulkan image and uses `vkCmdCopyImage` from the game's swapchain image. The Quest side then imports the buffer and blits it into the real Quest OpenXR swapchain. The first realistic optimization is to expose AHardwareBuffer-backed transport images directly as Windows OpenXR swapchain images so DXVK/the application renders into them, eliminating the producer-side copy.

Do **not** make this the first implementation milestone. Directly sharing the Quest runtime's own swapchain images across the Windows/Wine boundary may be unsupported because of device, process, ownership, layout, and synchronization constraints. Implement capability probing and retain the proven copy path as fallback.

Measure separately:

- producer render-to-copy GPU time
- transport synchronization wait
- Quest import/blit GPU time
- end-to-end frame age

Only call the path zero-copy when the measured producer copy is genuinely gone; importing an AHardwareBuffer followed by a blit is not full zero-copy.

### 3. Profile and tune CPU scheduling and transport timing — keep after instrumentation

Official immersive mode already requests sustained-high CPU/GPU performance, a render-thread hint, and 72 Hz. Do not add duplicate boost calls or hard-pin all Wine/FEX/Android threads.

First collect Perfetto/OVR Metrics and per-stage timestamps. Then tune only identified hot threads such as the Quest frame loop, transport receiver, control server, and producer submission thread. Prefer thread hints/priority and bounded wakeups over global affinity. Confirm thermal behavior over a sustained run, not only startup FPS.

### 4. Update FEX and use lower-memory JIT caches — separate post-parity workstream

Official GameNative already uses FEX 2605 in relevant configurations; FEX 2608 was current during this audit. Upstream FEX 2605 and 2608 already default `DisableL2Cache=true` and `DynamicL1Cache=true`, which reduce memory use with possible compilation/stutter tradeoffs. Do not blindly force conflicting environment values.

After functional parity:

1. package/test the newer supported FEX release through GameNative's normal version mechanism
2. log effective cache settings, not merely selected preset names
3. benchmark memory, translation time, average frame time, and p95/p99 frame time
4. keep upstream defaults unless measurements justify a per-container option

`EnableLazyCodeCachingWIP` exists in newer FEX but is explicitly WIP and default-off. Do not enable it globally.

Reference: `https://github.com/FEX-Emu/FEX/releases/tag/FEX-2608`

### 5. Beat Saber-specific TSO disabling metadata — discard

Do not implement it. FEX warns that disabling TSO can break multithreaded applications, and a generic GameNative VR runtime should not ship a one-title unsafe exception without a broad, reproducible compatibility framework.

### 6. Compositor-aware pacing instead of generic 2:1 repetition — keep after parity

Replace the arbitrary frame divisor with timing derived from the single Quest compositor loop:

- Quest `xrWaitFrame` predicted display time/period drives the Windows runtime's `xrWaitFrame` response.
- Submit each newly completed stereo pair when ready.
- If the producer misses a compositor interval, reuse the latest complete safe pair; do not block the Quest frame loop waiting indefinitely.
- Track frame age and missed producer/compositor intervals.
- Keep explicit half-rate mode only as a user-selected fallback, not the universal default.

Avoid generic sleeps and DXVK latency-sleep options until timestamps show where latency is introduced.

## Build and packaging requirements

- Keep Windows runtime/unixlib artifacts scoped to `modernXr` so normal APKs do not absorb unnecessary payloads.
- Use the official OpenXR loader/Prefab dependency for Android native code.
- Make every generated native artifact reproducible and document its source/build command.
- Fail the build when an expected runtime DLL/unixlib is absent or the wrong architecture, rather than shipping a silent partial payload.
- Preserve release stripping while retaining a symbol/archive strategy for crash diagnosis.
- Do not run expensive hashes or directory scans during frame processing.

## Validation matrix and milestone order

Use this order and do not skip directly to broad game testing:

1. Official flat immersive regression test with Windows VR disabled.
2. Native debug stereo producer in the official single session.
3. Windows native OpenXR x64 D3D11 probe.
4. Known working native OpenXR Unity/D3D11 title.
5. Input, haptics, pause/resume, menu, and transport-loss recovery.
6. One OpenVR/OpenComposite title with visible output.
7. Additional Unity versions, then a non-Unity engine.
8. Only then validate Vulkan-client, D3D12, and 32-bit paths.

For every test record:

- app/API/graphics/architecture
- exact GameNative, Wine/Proton, FEX, DXVK, and payload versions
- runtime connected
- session began
- first complete stereo pair
- first projection layer submitted
- visible image
- input/haptics
- flat suspension/fallback
- clean teardown
- average, p95, and p99 frame timing plus memory after a sustained interval

“Session began” is not equivalent to “working.”

## Commit strategy

Keep changes reviewable:

1. hook/service skeleton and flat regression
2. reproducible payload/build integration
3. native projection mode with debug producer
4. Windows control/data transport
5. native OpenXR D3D11 end-to-end
6. flat-presentation suspension and fallback
7. official quick-menu settings/diagnostics
8. OpenComposite compatibility
9. measured performance changes

Each commit must build and preserve the previous milestone. Avoid a single merge commit containing the entire runtime, transport, UI, and performance work at once.

## Definition of done

The migration is complete only when:

- Official `ImmersiveXrActivity` is the sole Quest activity/session for flat and stereo modes.
- Normal GameNative container settings and arguments are demonstrably the effective launch settings.
- Official flat immersive mode remains functional as startup and runtime fallback.
- The proven x64 native OpenXR D3D11 path produces a visible stereo image with input and clean teardown.
- Redundant flat presentation stops during stable stereo and resumes after persistent loss.
- OpenComposite is isolated as an optional adapter and its actual validation status is documented.
- Runtime payload installation/restoration is crash-safe and game-agnostic.
- There is no second VR activity/menu/settings fork, vendored OpenXR header snapshot, or Wine 9.2 pin in the new branch.
- Diagnostics identify the selected path and first failing stage without relying on Beat Saber-specific files.
- Performance claims are backed by frame-time, memory, and sustained thermal measurements.
- The compatibility matrix distinguishes proven, partial, compiled-only, and unsupported paths.

## Final reporting format

When handing the implementation back, report:

1. official `master` base SHA and final implementation SHA
2. files/components added, modified, and architectural paths intentionally omitted
3. working compatibility matrix with evidence
4. exact remaining blockers by pipeline stage
5. before/after CPU, GPU, frame-time, latency, and RAM measurements
6. payload/build reproduction instructions
7. any manual device tests still required

Do not report the project as generally SteamVR-compatible until at least one native OpenXR title and one OpenVR/OpenComposite title have each produced a visible, interactive image through the new single-session architecture.
