# Debugging Report: "Ogu and the Secret Forest" Crash on Save Load

## Issue Summary
When attempting to continue / load a saved game in **"Ogu and the Secret Forest"** (Epic Games) on GameNative (Odin 3, Proton 9.0 ARM64EC, Turnip driver), the game boots successfully to the title screen and intro cutscenes, but immediately crashes upon loading a save file.

---

## Logcat & Crash Analysis
Inspection of the captured logcat dump (`ogu_crash.txt`) reveals the exact failure mode:

```text
09-04 18:43:32.301 I/System.out(16096): 03f0:trace:loaddll:build_module Loaded L"C:\\Program Files (x86)\\Epic Games\\Launcher\\Portal\\Extras\\Overlay\\Win64\\libcef.dll" at 0000004FE8EE0000: native
...
09-04 18:43:32.601 I/System.out(16096): 03f0:err:virtual:allocate_virtual_memory out of memory for allocation, base 0x0 size 11000000000
09-04 18:43:32.602 I/System.out(16096): 03f0:err:virtual:allocate_virtual_memory out of memory for allocation, base 0x0 size 8000000000
09-04 18:43:32.602 I/System.out(16096): 03f0:err:virtual:allocate_virtual_memory out of memory for allocation, base 0x0 size 4000000000
```

### Root Cause
1. **Epic Online Services (EOS) Overlay**: GameNative automatically provisions the EOS Overlay (`EpicOverlayDependency`) for Epic Games, which installs `EOSOverlayRenderer-Win64-Shipping.exe` and `libcef.dll` (Chromium Embedded Framework) into the Wine prefix (`drive_c/Program Files (x86)/Epic Games/Launcher/Portal/Extras/Overlay`).
2. **Virtual Memory Exhaustion**: When the game triggers save loading or EOS online interactions, the overlay renderer initializes CEF. CEF attempts massive 64-bit virtual memory address space reservations (`VirtualAlloc` with `MEM_RESERVE` ranging from 4GB to 11GB).
3. **Android / Wine Address Space Limits**: Wine running on Android / ARM64 EC enforces virtual memory limits. When CEF requests massive address space chunks, Wine fails with `allocate_virtual_memory out of memory`, crashing the helper process and triggering a cascading crash (`java.io.IOException: Failed to write data` / X11 connection drop) in `OguForest.exe`.

---

## Resolution / Workaround

Because the EOS Overlay is optional for core gameplay (it only provides the in-game Epic social/purchase HUD and notifications), disabling or removing the EOS overlay from the container prevents CEF from exhausting virtual memory.

### How to Fix / Disable EOS Overlay for This Game:
1. **Remove Overlay Files**: Inside your container's wine prefix directory:
   `AppData/Local/Winlator/.../.wine/drive_c/Program Files (x86)/Epic Games/Launcher/Portal/Extras/Overlay`
   Delete the `Overlay` folder (or remove the overlay using GameNative's `EpicOverlayManager.removeOverlay`).
2. **Clear Registry Path**: Ensure `HKCU\\SOFTWARE\\Epic Games\\EOS\\OverlayPath` in `user.reg` is cleared (set to empty or removed).
3. **Launch Game**: Restart the game. Without the EOS overlay renderer attempting gigabyte-sized virtual memory allocations, saving and loading will proceed without crashing.
