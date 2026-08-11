# Performance Control Architecture for GameNative

## Overview

GameNative's performance control system provides CPU and GPU tuning capabilities for Android gaming devices. It supports multiple device types through an extensible driver architecture, including PServer-based devices (AYN Odin, Retroid Pocket) and Samsung Galaxy devices via the Samsung Performance SDK. The system allows users to adjust CPU governors, frequency scaling, and GPU performance levels to optimize game performance. Additionally, it features an **automatic performance tuning system** that uses PID controllers to dynamically adjust CPU/GPU settings based on target FPS and real-time utilization metrics, maintaining optimal performance while minimizing resource consumption.

## Architecture

### Core Components

1. **PerformanceDriver** (Abstract Base Class)
   - Location: `drivers/PerformanceDriver.kt`
   - Defines the interface for all device-specific performance drivers
   - Provides common functionality like frequency formatting
   - **Abstract methods** (must be implemented):
     - `isDriverSupported()` - Driver availability detection
     - `getDisplayUnit()` - Returns display unit for frequency values (HZ or INTEGER)
   - **Open methods with defaults** (can be overridden):
     - `isGovernorSupported()` - CPU governor control support (default: false)
     - `isGpuSupported()` - GPU control support (default: false)
     - `isBusSupported()` - RAM bus control support (default: false)
     - `isFanSupported()` - Fan control support (default: false)
     - `start()` - Initialize driver when game starts (default: no-op)
     - `stop()` - Cleanup driver when game stops (default: no-op)
     - `beginUpdate()` - Begin batch update session (default: no-op)
     - `commit()` - Commit pending updates (default: returns true)
     - `getDefaultProfile()` - Returns default Balanced profile for the device
     - CPU: `getCurrentMinCpuValue()`, `getCurrentMaxCpuValue()`, `getCurrentGovernor()`
     - CPU: `setMinCpuValue(value)`, `setMaxCpuValue(value)`, `setGovernor(governor)`
     - CPU: `getAvailableGovernors()`, `getAvailableCpuFrequencies()`
     - GPU: `getCurrentGpuValue()`, `getAvailableGpuFrequencies()`
     - GPU: `getCurrentMinGpuPowerLevel()`, `getCurrentMaxGpuPowerLevel()`, `getNumGpuPowerLevels()`
     - GPU: `setMinGpuPowerLevel(level)`, `setMaxGpuPowerLevel(level)`
     - Bus: `getCurrentMinBusLevel()`, `getCurrentMaxBusLevel()`, `getNumBusLevels()`
     - Bus: `setMinBusLevel(level)`, `setMaxBusLevel(level)`

2. **PServerDriver** (Implementation)
   - Location: `drivers/PServerDriver.kt`
   - Concrete implementation for devices with PServer support
   - Supports: AYN Odin, Retroid Pocket devices
   - **Integrated binder interface** - Directly communicates with PServerBinder service
   - Manages all sysfs paths for CPU and GPU control
   - Implements fallback to direct file reads when PServer unavailable
   - Uses Android Binder IPC via reflection to access ServiceManager
   - GPU support for Adreno GPUs (Qualcomm Snapdragon devices)
   - **Command optimization**: Concatenates chmod → echo → chmod into single commands for faster execution
   - **Permission management**: Sets sysfs files to 444 (read-only) after writes, restores to 644 on stop
   - **Policy-based CPU control** (inspired by [GameMode](https://github.com/FeralInteractive/gamemode)):
     - Discovers CPU policies by resolving symlinks at initialization
     - Eliminates redundant writes to CPUs sharing the same policy
     - Reduces IPC calls by 50-75% on typical multi-core devices
     - Falls back to per-CPU approach if policy discovery fails

3. **SamsungPerformanceDriver** (Implementation)
   - Location: `drivers/SamsungPerformanceDriver.kt`
   - Concrete implementation using Samsung Performance SDK
   - Supports: Samsung Galaxy devices running Android 10+ (since March 2020)
   - Uses performance levels (1-4) instead of raw frequencies (level 0 = disabled)
   - Communicates with system daemon via socket (requires INTERNET permission)
   - Supports CPU and GPU performance control through CustomParams API
   - No CPU governor control (Samsung SDK manages this internally)
   - **Lifecycle**: `start()` is no-op (controls started by individual setters), `stop()` calls `performanceManager.stop()` to stop all active controls

4. **PowerManager** (Facade)
   - Location: `PowerManager.kt`
   - High-level API for UI components
   - Delegates all operations to the active PerformanceDriver
   - Provides data classes: `CpuInfo`, `GpuInfo`
   - Exposes methods for CPU governor, frequency, and GPU control
   - **Profile Management**: Tracks `currentProfile` and synchronizes it with driver state
   - **Profile Persistence**: Saves/restores profiles via `PrefManager` using JSON serialization
   - **Automatic Sync**: All setter methods update both driver and `currentProfile` data
   - **Auto-Tuning Management**:
     - Tracks target FPS (from XServer frame rate limiter)
     - Tracks current FPS, CPU usage, GPU usage (from Performance HUD)
     - Manages `PerformanceAutoTuner` lifecycle (start/stop)
     - Provides callbacks for auto-tuner to adjust CPU/GPU settings
   - Maintains backward compatibility with existing UI code

5. **PowerProfile** (Data Class)
   - Location: `PowerProfile.kt`
   - Serializable data class representing a complete performance configuration
   - Fields (all mutable `var`):
     - `enableAutoTuning: Boolean` - Enable automatic performance tuning (default: false)
     - `name: String` - Profile name (e.g., "Balanced", "Performance", "Custom")
     - `governor: CpuGovernor` - CPU governor enum
     - `minCpuFreq: Long` - Minimum CPU frequency/level
     - `maxCpuFreq: Long` - Maximum CPU frequency/level
     - `minGpuPowerLevel: Int` - Minimum GPU power level (default: 0)
     - `maxGpuPowerLevel: Int` - Maximum GPU power level (default: 0)
   - Used for profile persistence, UI state, and default profiles

6. **PowerProfiles** (Object)
   - Location: `PowerProfile.kt`
   - Provides `getDefaultProfiles()` factory method
   - Generates device-specific preset profiles:
     - **Power Save**: Low frequencies (25% CPU, 25% GPU), powersave governor
     - **Balanced**: Mid frequencies (50% CPU, 50-100% GPU), schedutil/conservative/interactive governor
     - **Performance**: High frequencies (75% CPU, 75-100% GPU), performance governor
     - **On Demand**: Full range (0-100% CPU/GPU), ondemand governor
     - **WALT**: Full range (0-100% CPU/GPU), walt governor
   - Dynamically calculates frequency tiers based on available frequencies
   - GPU power levels calculated as percentages of max GPU power level

7. **PerformanceAutoTuner** (Auto-Tuning)
   - Location: `autotuning/PerformanceAutoTuner.kt`
   - Automatic performance tuner using PID controllers
   - Dynamically adjusts CPU frequencies, GPU power levels and, when supported, RAM bus level based on target/current FPS; CPU/GPU utilization also informs CPU/GPU adjustments:
     - Target FPS (from XServer frame rate limiter)
     - Current FPS (from Performance HUD)
     - CPU usage percentage
     - GPU usage percentage
   - **Adaptive Performance Scaling**:
     - Reduces performance when hitting target FPS with low utilization
     - Increases performance when missing target FPS or high utilization
     - Uses PID controllers for smooth, responsive adjustments
   - **Configurable Thresholds**:
     - FPS error threshold: 2.0 FPS (small), 5.0 FPS (large)
     - Usage low threshold: 70% (reduce performance)
     - Usage high threshold: 85% (increase performance)
     - Performance range: 20-100%
   - **Tuning Cycle**: Runs every 2 seconds on background thread
   - **Integration**: Enabled via `PowerProfile.enableAutoTuning` flag

8. **PidController** (Control Theory)
   - Location: `autotuning/PidController.kt`
   - Proportional-Integral-Derivative controller for smooth performance adjustments
   - **Tuning Parameters**:
     - Kp (Proportional gain): 0.5 - Immediate response to FPS error
     - Ki (Integral gain): 0.2 - Eliminates steady-state error over time
     - Kd (Derivative gain): 0.1 - Dampens oscillations for stability
   - **Anti-Windup Protection**: Integral term limited to ±50.0
   - **Output Clamping**: Constrains output to valid range (-100.0 to +100.0)
   - **State Management**: Tracks integral, previous error, and time delta
   - Separate controllers for CPU and GPU tuning

## Supported Features

### Current (PServerDriver)

**CPU Control:**
- ✅ CPU governor control
- ✅ CPU frequency scaling (min/max)
- ✅ Multiple governor support (schedutil, performance, powersave, etc.)
- ✅ Sysfs file permission management (chmod 444 after write, restore to 644 on stop)
- ✅ **CPU Pinning / Process Affinity Control**:
  - ~~Automatic app process pinning to efficiency cores~~ (Removed due to possible ANR happening)
  - Automatic PulseAudio pinning to dedicated performance core
  - Wine game process pinning with retry logic
  - Wine infrastructure pinning (wineserver, winhandler, services.exe)
  - Cluster-based core selection (EFFICIENCY, PERFORMANCE, PRIME)
  - Wine-aware PID discovery via `/proc/cmdline` scanning

**GPU Control (Adreno):**
- ✅ GPU power level control (min/max power levels)
- ✅ Power level range detection (`num_pwrlevels`)
- ✅ Available GPU frequencies enumeration (read-only)
- ✅ Current GPU frequency monitoring (read-only)
- ✅ Sysfs file permission management (chmod 444 after write, restore to 644 on stop)
- ❌ Direct GPU frequency setting (not supported by hardware)

**Auto-Tuning:**
- ✅ PID controller-based automatic performance tuning
- ✅ Dynamic CPU frequency adjustment based on FPS and CPU usage
- ✅ Dynamic GPU power level adjustment based on FPS and GPU usage
- ✅ Adaptive performance scaling (reduce when over-performing, increase when under-performing)
- ✅ Configurable thresholds for FPS error and resource utilization
- ✅ Background tuning thread with 2-second cycle interval
- ✅ Integration with XServer frame rate limiter for target FPS
- ✅ Integration with Performance HUD for current metrics (FPS, CPU/GPU usage)
- ✅ Separate PID controllers for CPU and GPU with anti-windup protection

**Lifecycle:**
- ✅ `start(containerDir)` - Restores saved profile from container-specific file (or applies default Balanced profile)
  - **Automatically pins app process to efficiency cores**
  - **Automatically pins PulseAudio to first performance core**
  - **Starts auto-tuning if enabled in profile**
- ✅ `stop()` - **Critical performance restoration**:
  1. **Stops auto-tuning thread and resets PID controllers**
  2. Resets CPU frequencies to full range (min to max available)
  3. Resets GPU power levels to full range (0 to max)
  4. Restores CPU governor to first available governor
  5. Restores all modified sysfs files to 644 permissions
  6. **Resets app process CPU affinity to all cores**
  - Runs asynchronously on background thread
  - **Prevents device slowness** when exiting from Power Save mode

### CPU Pinning (Process Affinity Control)

**Overview:**
CPU pinning assigns specific processes to dedicated CPU cores to optimize performance by:
- Reducing thread migration overhead (50-75% reduction)
- Improving cache locality (fewer cache invalidations)
- Preventing interference between critical processes
- Ensuring prime cores boost to maximum frequency

**Automatic Pinning (Driver-Level):**

The PServerDriver automatically handles CPU pinning when started/stopped:

*On `start()`:*
- **App Process** → Pinned to EFFICIENCY cores (CPUs 0-2 on typical devices)
  - Frees up performance cores for game processes
  - UI/overlay doesn't need high-frequency cores
  - Reduces power consumption
- **PulseAudio** → Pinned to first PERFORMANCE core (CPU 3 on typical devices)
  - Dedicated core for low-latency audio
  - Prevents audio crackling/underruns
  - No cache contention with game

*On `stop()`:*
- **App Process** → Reset to all available cores
  - Restores default Android scheduler behavior
  - Ensures UI responsiveness when not gaming

**Manual Pinning (Game & Wine Infrastructure):**

Game processes and Wine infrastructure are pinned via PowerManager methods:

*Game Process Pinning:*
```kotlin
PowerManager.pinGameWithRetry(
    processName = "DaveTheDiver.exe",
    maxRetries = 10,
    retryDelayMs = 1000
)
```
- Uses Wine-aware PID discovery (scans `/proc/cmdline` for `.exe` processes)
- Retries up to 10 times with 1 second delay
- Pins to PERFORMANCE + PRIME cores (CPUs 3-7 on typical devices)
- Logs success/failure with attempt count

*Wine Infrastructure Pinning:*
```kotlin
PowerManager.pinWineInfrastructure()
```
- **wineserver** → PERFORMANCE cores (CPUs 3-6) - Critical for Wine IPC
- **winhandler.exe** → PERFORMANCE + PRIME cores (CPUs 4-7) - Window management
- **services.exe** → First 2 PERFORMANCE cores (CPUs 3-4) - Windows services
- Waits 2 seconds for Wine to fully initialize
- Logs each process pinning result

**Cluster-Based Core Selection:**

PServerDriver uses cluster-based core selection for device-agnostic pinning:

```kotlin
// Get cores by cluster type
val effCores = driver.getCpuCoresByCluster(CpuCluster.EFFICIENCY)    // CPUs 0-2
val perfCores = driver.getCpuCoresByCluster(CpuCluster.PERFORMANCE)  // CPUs 3-6
val primeCores = driver.getCpuCoresByCluster(CpuCluster.PRIME)       // CPU 7

// Pin to specific cluster
driver.setCpuAffinityByCores(pid, perfCores)
```

**CPU Cluster Types:**
- `EFFICIENCY` - Lowest frequency cores (power-saving)
- `PERFORMANCE` - Mid-high frequency cores (balanced)
- `PRIME` - Highest frequency core(s) (peak performance)

**Complete CPU Allocation (Snapdragon 8 Gen 2 Example):**

```
CPU 0-2 (Efficiency @ 2.0 GHz):  GameNative app, Android system
CPU 3 (Performance @ 2.8 GHz):    PulseAudio, wineserver, services.exe, game
CPU 4-6 (Performance @ 2.8 GHz): wineserver, services.exe, game, winhandler
CPU 7 (Prime @ 3.2 GHz):         game, winhandler (BOOST!)
```

**Performance Impact:**

*Before Pinning:*
- FPS: 40-45 (unstable)
- Prime core frequency: 2.476 GHz (underutilized)
- Frame pacing: Inconsistent (stutters)
- Audio: Occasional crackling

*After Pinning:*
- FPS: 58-60 (stable)
- Prime core frequency: 3.0-3.2 GHz (boosted)
- Frame pacing: Smooth, consistent
- Audio: Crystal clear, no glitches
- Cache efficiency: Improved (no contention)

**Wine-Aware PID Discovery:**

For Wine games, standard `pidof` doesn't work because:
- Wine processes appear as Linux processes with Wine executable names
- The actual game executable is in `/proc/<pid>/cmdline`

PServerDriver provides `findWineProcessPid()` to scan `/proc`:
```kotlin
driver.findWineProcessPid("DaveTheDiver.exe")  // Returns PID or null
```

**Integration Example:**

```kotlin
// In XServerScreen.kt after game environment setup
PowerManager.start(container.rootDir)  // Auto-pins app + PulseAudio, loads per-container profile

// Pin game process
val executableName = container.executablePath
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .takeIf { it.isNotEmpty() }
    ?.let { name ->
        val baseName = name.substringBefore(".exe", name)
        PowerManager.pinGameWithRetry(
            processName = "$baseName.exe",
            maxRetries = 10,
            retryDelayMs = 1000
        )
    }

// Pin Wine infrastructure
PowerManager.pinWineInfrastructure()
```

**Expected Logs:**
```
PServerDriver: Pinned app process (PID: 12345) to efficiency CPUs 0, 1, 2
PowerManager: Pinned PulseAudio (PID: 10642) to CPU 3
PowerManager: Pinned DaveTheDiver.exe (PID: 11540) to CPUs 3, 4, 5, 6, 7 after 1 attempts
PowerManager: Pinned wineserver (PID: 11309) to CPUs 3, 4, 5, 6
PowerManager: Pinned winhandler.exe (PID: 11536) to CPUs 3, 4, 5, 6, 7
PowerManager: Pinned services.exe (PID: 11342) to CPUs 3, 4
```

**Manual Testing (ADB):**
```bash
# Get game PID
adb shell ps -A | grep -i dave

# Pin to CPUs 4-7
adb shell su -c "taskset -p 0xf0 <pid>"

# Verify affinity
adb shell su -c "taskset -p <pid>"
```

### GameMode-Inspired Improvements

**Overview:**
PServerDriver incorporates optimizations inspired by [Feral Interactive's GameMode](https://github.com/FeralInteractive/gamemode), a Linux daemon for optimizing system performance on demand. These improvements reduce IPC overhead by **50-75%** on typical multi-core devices.

**Policy-Based CPU Control:**

*Discovery Phase (at driver start):*
- Resolves symlinks for each CPU's `scaling_governor` file using `File.canonicalPath`
- Groups CPUs by their actual policy directory (e.g., `/sys/devices/system/cpu/cpufreq/policy0`)
- Creates a `CpuPolicy` object for each unique policy containing all associated CPU cores
- Cached for subsequent operations (only discovered once per driver lifecycle)

*Benefits:*
- **50-75% reduction in IPC calls** on devices with shared policies (typical for modern SoCs)
- Example: 8-core device with single policy → 3 IPC calls instead of 24 (87.5% reduction)
- Eliminates redundant writes to CPUs sharing the same cpufreq policy
- More robust against race conditions from concurrent policy modifications

*Fallback Behavior:*
- If policy discovery fails, falls back to per-CPU approach (legacy behavior)
- Ensures compatibility with all device configurations
- Logs detailed information about discovered policies for debugging

**CPU Policy Data Structure:**

```kotlin
private data class CpuPolicy(
    val policyId: Int,
    val governorPath: String,
    val minFreqPath: String,
    val maxFreqPath: String,
    val cpuCores: List<Int>,
    val maxFrequency: Long  // Maximum frequency for this policy
)
```

**Sysfs Validation:**

The driver validates CPU frequency scaling support at initialization:

*Validates:*
- `/sys/devices/system/cpu` - CPU base directory
- `/sys/devices/system/cpu/cpufreq` - CPUFreq directory
- `/sys/devices/system/cpu/cpufreq/policy0` - Policy0 directory
- `/sys/devices/system/cpu/cpufreq/policy0/scaling_governor` - Governor file

*Benefits:*
- Early detection of missing cpufreq support
- Helpful error messages for troubleshooting
- Prevents confusing errors later in execution

**CPU Cluster Identification:**

The driver automatically identifies CPU clusters based on frequency capabilities:

```kotlin
enum class CpuCluster {
    EFFICIENCY,    // Lowest frequency cores
    PERFORMANCE,   // Mid-high frequency cores
    PRIME          // Highest frequency core(s)
}
```

*Discovery Process:*
- Sorts policies by maximum frequency
- Assigns cluster types based on policy count and frequency ranges
- Supports dual-cluster (big.LITTLE) and tri-cluster (efficiency + performance + prime) configurations
- Provides cluster-to-core mapping for CPU pinning

**Frequency Capping per Policy:**

When setting CPU frequencies, the driver respects each policy's maximum frequency:

```kotlin
// In setMinCpuValue() and setMaxCpuValue()
val cappedValue = min(value, policy.maxFrequency)
writeSysfsFile(policy.minFreqPath, cappedValue.toString())
```

*Benefits:*
- Prevents attempting to set impossible frequencies
- Ensures each core operates within its hardware capabilities
- Logs capping operations for debugging

**Internal State Tracking:**

The driver tracks the last requested values instead of reading from sysfs:

```kotlin
private var currentMinCpuFreq: Long = 0L
private var currentMaxCpuFreq: Long = 0L
private var currentGovernor: String = ""
```

*Benefits:*
- `getCurrentMinCpuValue()`, `getCurrentMaxCpuValue()`, `getCurrentGovernor()` return intended values
- Consistent with what was actually requested
- Avoids confusion when policies have different values

**Comprehensive Frequency Discovery:**

`getAvailableCpuFrequencies()` now collects frequencies from all CPU policies:

```kotlin
for (policy in cpuPolicies) {
    val freqs = readSysfsFile("$policyDir/scaling_available_frequencies")
    allFrequencies.addAll(freqs)
}
```

*Benefits:*
- Includes frequencies from all CPU clusters
- Provides complete frequency range for UI
- Accurate frequency selection for profiles

### Current (SamsungPerformanceDriver)

**CPU Control:**
- ✅ CPU performance level control (1-4 scale, 0 = disabled)
- ✅ Min/Max CPU performance levels
- ✅ Automatic timeout management (0 = indefinite)
- ❌ CPU governor control (managed by Samsung SDK)
- ❌ Raw frequency control (uses performance levels)

**GPU Control:**
- ✅ GPU performance level control (1-4 scale, 0 = disabled)
- ✅ Min/Max GPU performance levels
- ✅ Automatic resource management
- ❌ Direct GPU frequency setting (not supported)

**Lifecycle:**
- ✅ `start(containerDir)` - Restores saved profile from container-specific file (or applies default Balanced profile)
- ✅ `stop()` - Calls `performanceManager.stop()` to stop all active performance controls. PowerManager saves the current profile before calling this method.

### Future Candidates
- ⏳ Fan speed control
- ⏳ Additional device-specific drivers
- ⏳ Mali GPU support (non-Samsung)

## Key Design Decisions

### Generic Abstraction
- **Parameter naming**: Methods use generic `value: Long` instead of `frequency: Long`
- **Generic comments**: Base class uses "CPU performance value" instead of "frequency in KHz"
- **Implementation-specific docs**: PServerDriver specifies "frequency in KHz" in its documentation
- This allows future drivers to use different units (percentages, performance levels, etc.)

### Display Units

The `PerformanceDriver.DisplayUnit` enum defines how frequency values are displayed:
- `HZ`: Raw hertz values (e.g., 2400000000 Hz)
- `INTEGER`: Human-readable format (e.g., 2.4 GHz)

Currently:
- PServerDriver uses `INTEGER` format
- SamsungPerformanceDriver uses `INTEGER` format (for performance levels)

### GPU Power Level Semantics

All drivers expose a **normalized power level interface** where **higher = better performance**.

**Unified API Semantics (All Drivers):**
- Level 0 = Minimum performance
- Level N = Maximum performance
- Higher power level = Better performance
- This applies to both `getCurrentMinGpuPowerLevel()` and `getCurrentMaxGpuPowerLevel()`

**Driver-Specific Internal Handling:**

*PServerDriver (Adreno GPUs):*
- Adreno sysfs uses reversed indexing: `max_pwrlevel = 0` (fastest), higher index = slower
- PServerDriver **internally converts** between UI semantics and sysfs semantics
- Conversion: `sysfs_level = numGpuPowerLevels - 1 - ui_level`
- UI code doesn't need to know about this reversal

*SamsungPerformanceDriver:*
- Samsung SDK uses natural ordering: Level 1-4 where higher = better
- No conversion needed, passes values directly

**GPU Power Level Controls:**
- `setMinGpuPowerLevel(level)` - Sets minimum performance cap via `min_pwrlevel`
- `setMaxGpuPowerLevel(level)` - Sets maximum performance cap via `max_pwrlevel`
- GPU frequency is read-only and managed by the GPU governor within the power level constraints

### Profile Persistence

PowerManager automatically saves and restores performance profiles across app sessions:

**Persistence Mechanism:**
- Profiles are serialized to JSON using `kotlinx.serialization`
- Stored per-container in `<container_dir>/.config/.power-profile`
- Each container maintains its own independent power profile
- Includes all profile fields: name, governor, CPU frequencies, GPU power levels, bus levels, auto-tuning settings

**Lifecycle Integration:**
1. **On `start(containerDir)`**:
   - Accepts container directory path for per-container profile storage
   - Attempts to restore saved profile from `<container_dir>/.config/.power-profile`
   - If no saved profile exists, applies default Balanced profile from `driver.getDefaultProfile()`
   - Applies the profile to hardware via driver methods
   - `.config` directory is created automatically when the profile is saved

2. **On `stop()`**:
   - Saves `currentProfile` to container-specific file (if not null)
   - Ensures user's last settings are preserved for next session per container

3. **During Runtime**:
   - All setter methods (`setGovernor`, `setMinCpuValue`, etc.) automatically update `currentProfile`
   - Profile name is set to "Custom" when individual settings are changed
   - Profile name is preserved when applying preset profiles

**Profile Synchronization:**
- `PowerManager.currentProfile` always reflects the actual driver state
- Setter methods update both driver and `currentProfile` atomically
- Only updates profile if driver operation succeeds
- Ensures persistence data matches hardware state

**UI Integration:**
- UI matches profiles by name against `PowerManager.currentProfile?.name`
- Dropdown shows "Custom" when settings don't match any preset
- Profile selection updates both UI state and PowerManager immediately

## Driver Selection

PowerManager automatically selects the appropriate driver during initialization:

1. **Samsung Performance SDK** - Checked first for Samsung devices
2. **PServer** - Checked for AYN Odin, Retroid Pocket devices
3. **Fallback** - Uses NoOpPerformanceDriver (no functionality)

The selection happens in `PowerManager.initialize(context)` which should be called during app startup.

## Driver Lifecycle

Drivers follow a game lifecycle pattern with automatic profile persistence:

1. **Game Environment Setup** (`XServerScreen.kt`)
   - After `PluviaApp.xEnvironment` is initialized
   - `PowerManager.start(container.rootDir)` is called with container directory
   - **Profile Restoration**:
     - Attempts to load saved profile from `<container_dir>/.config/.power-profile`
     - If no saved profile, applies default Balanced profile from `driver.getDefaultProfile()`
     - Applies profile settings to hardware via driver methods
   - Driver-specific initialization occurs

2. **Game Running**
   - User can adjust performance settings via UI
   - Each setting change calls the appropriate driver method
   - **Automatic Profile Sync**:
     - All setter methods update both driver and `PowerManager.currentProfile`
     - Profile name changes to "Custom" when individual settings are modified
     - Preset profile names are preserved when applying complete profiles
   - PServerDriver: Writes to sysfs and sets files to 444 (read-only)
   - SamsungPerformanceDriver: Calls `performanceManager.start(params)` with new settings

3. **Game Environment Shutdown** (`PluviaApp.shutdownEnvironment()`)
   - `PowerManager.stop()` is called
   - **Profile Persistence**:
     - Saves `currentProfile` to container-specific file for next session
   - **Hardware Restoration**:
     - PServerDriver:
       1. Resets CPU frequencies to full range (prevents slowness from Power Save)
       2. Resets GPU power levels to full range
       3. Restores CPU governor to first available governor
       4. Restores all modified sysfs files to 644 permissions
     - SamsungPerformanceDriver: Calls `performanceManager.stop()` to stop all performance controls

## Adding New Drivers

To add support for a new device:

1. Create a new driver class in `drivers/` folder extending `PerformanceDriver`
2. Implement all abstract methods according to device capabilities:
   - **Required**: `getDefaultProfile()` - Return a Balanced profile for the device
     - Should return middle-performance settings (50% CPU, 50% GPU)
     - Use `PerformancePreset.BALANCED.displayName` for the profile name
     - Calculate appropriate values based on device's available frequencies/levels
3. Add necessary imports:
   ```kotlin
   import app.gamenative.powercontrol.PowerProfile
   import app.gamenative.powercontrol.profiles.CpuGovernor
   import app.gamenative.powercontrol.profiles.PerformancePreset
   ```
4. Update `PowerManager.initialize()` to include the new driver in the selection logic:

```kotlin
fun initialize(context: Context) {
    driver = when {
        NewDriver(context).isDriverSupported() -> NewDriver(context)
        SamsungPerformanceDriver(context).isDriverSupported() -> SamsungPerformanceDriver(context)
        PServerDriver().isDriverSupported() -> PServerDriver()
        else -> NoOpPerformanceDriver() // Fallback
    }
}
```

## Implementation Details

### SamsungPerformanceDriver

**Samsung Performance SDK Integration:**
- Uses `com.samsung.sdk.sperf` package
- Requires `perfsdk-v1.0.0.jar` in `app/src/main/lib/`
- Requires `INTERNET` permission in AndroidManifest.xml
- Initializes SDK with `SPerf.initialize(context)`
- Creates `PerformanceManager` instance for control

**Performance Levels:**
Samsung SDK uses performance levels (1-4) instead of raw frequencies:
- Level 0: Disabled (system default, not used in driver)
- Level 1: Low performance
- Level 2: Medium performance
- Level 3: High performance
- Level 4: Maximum performance

**CustomParams API:**
```kotlin
val params = CustomParams()
params.add(CustomParams.TYPE_CPU_MIN, level, timeout)
performanceManager.start(params)
```

Available parameter types:
- `TYPE_CPU_MIN` - Minimum CPU performance level
- `TYPE_CPU_MAX` - Maximum CPU performance level
- `TYPE_GPU_MIN` - Minimum GPU performance level
- `TYPE_GPU_MAX` - Maximum GPU performance level

**Timeout Management:**
- Timeout in milliseconds (0 = indefinite)
- Performance controls auto-stop when timeout reached
- `performanceManager.stop()` called automatically when game stops (via `SamsungPerformanceDriver.stop()`)

**Start/Stop Behavior:**
- `start()`: No-op - Performance controls are started individually by setter methods
  - Each setter (`setMinCpuValue`, `setMaxCpuValue`, etc.) calls `performanceManager.start(params)`
  - This allows fine-grained control per parameter
- `stop()`: Calls `performanceManager.stop()` to stop ALL active performance controls
  - Called automatically when game environment shuts down
  - Releases all performance locks

**Preset Support:**
Samsung SDK also provides preset performance profiles:
- `PRESET_TYPE_CPU` - CPU intensive scenario
- `PRESET_TYPE_GPU` - GPU intensive scenario
- `PRESET_TYPE_BUS` - I/O or memory access-intensive scenario

Currently not used in SamsungPerformanceDriver (uses CustomParams for fine control).

### PServerDriver

**Policy-Based CPU Control (GameMode-inspired):**

The driver now uses an optimized policy-based approach for CPU control, inspired by [Feral Interactive's GameMode](https://github.com/FeralInteractive/gamemode):

*Discovery Phase (at driver start):*
- Triggered when `start()` is called (deferred initialization)
- Resolves symlinks for each CPU's `scaling_governor` file using `File.canonicalPath`
- Groups CPUs by their actual policy directory (e.g., `/sys/devices/system/cpu/cpufreq/policy0`)
- Creates a `CpuPolicy` object for each unique policy containing all associated CPU cores
- Cached for subsequent operations (only discovered once per driver lifecycle)

*Benefits:*
- **50-75% reduction in IPC calls** on devices with shared policies (typical for modern SoCs)
- Example: 8-core device with single policy → 3 IPC calls instead of 24 (87.5% reduction)
- Eliminates redundant writes to CPUs sharing the same cpufreq policy
- More robust against race conditions from concurrent policy modifications

*Fallback Behavior:*
- If policy discovery fails, falls back to per-CPU approach (legacy behavior)
- Ensures compatibility with all device configurations
- Logs detailed information about discovered policies for debugging

*Validation:*
- Checks for CPU frequency scaling support at initialization
- Validates existence of key sysfs paths (`/sys/devices/system/cpu/cpufreq/policy0`, etc.)
- Provides helpful warnings if cpufreq is disabled in kernel/BIOS

**Binder Service Communication:**
- Uses Android Binder IPC via reflection to access `ServiceManager`
- Connects to `PServerBinder` service on supported devices
- Executes root commands through binder transactions
- Parcel-based data encoding/decoding

**Sysfs Paths:**
All sysfs paths are encapsulated in `PServerDriver`:

*CPU:*
- Base: `/sys/devices/system/cpu`
- Policy: `/sys/devices/system/cpu/cpufreq/policy0`
- Per-CPU: `/sys/devices/system/cpu/cpu{N}/cpufreq/`

*GPU (Adreno):*
- Base: `/sys/class/kgsl/kgsl-3d0`
- Devfreq: `/sys/class/kgsl/kgsl-3d0/devfreq`
- Frequency: `/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq`
- Available frequencies: `/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies`
- Power levels: `/sys/class/kgsl/kgsl-3d0/min_pwrlevel`, `max_pwrlevel`
- Power level count: `/sys/class/kgsl/kgsl-3d0/num_pwrlevels`

**Fallback Mechanism:**
1. Try PServer binder service (primary)
2. Fall back to direct file read (if accessible)
3. Fail gracefully with logging

**Command Optimization:**
Commands are concatenated with semicolons for faster execution:
```kotlin
// Read operation
executeAsRoot("cat '/sys/devices/system/cpu/cpufreq/policy0/scaling_governor'")

// Write operation (single command instead of 3 separate calls)
executeAsRoot("chmod 644 '$path'; echo '$value' > '$path'; chmod 444 '$path'")

// Batch permission restoration on stop
executeAsRoot("chmod 644 '$path1'; chmod 644 '$path2'; chmod 644 '$path3'")
```

**Permission Management:**
- Write operations use concatenated commands: `chmod 644 → echo → chmod 444` (single IPC call)
- Files are set to 444 (read-only) after writes to prevent accidental modifications
- On `stop()`, all modified files are restored to 644 permissions using concatenated chmod commands
- Governor files are automatically added to restoration list after `setGovernor()` is called
- Tracks modified files in `modifiedSysfsFiles` set to ensure proper cleanup

### Auto-Tuning Implementation

**Overview:**
The auto-tuning system uses PID (Proportional-Integral-Derivative) controllers to automatically adjust CPU frequencies and GPU power levels based on real-time performance metrics. This maintains target FPS while optimizing resource consumption.

**Architecture:**

*PerformanceAutoTuner:*
- Manages two independent PID controllers (CPU and GPU)
- Runs on background thread with 2-second tuning cycles
- Monitors: target FPS, current FPS, CPU usage, GPU usage
- Adjusts performance levels between 20-100%
- Maps performance percentages to actual hardware frequencies/levels

*PidController:*
- Implements classic PID control algorithm
- **Proportional term (Kp=0.5)**: Immediate response to FPS error
- **Integral term (Ki=0.2)**: Eliminates steady-state error over time
- **Derivative term (Kd=0.1)**: Dampens oscillations for stability
- **Anti-windup protection**: Integral term clamped to ±50.0
- **Output clamping**: Constrains adjustments to ±100.0 range

**Tuning Logic:**

*Reduce Performance (Conservative):*
```
if (fpsError < 2.0 && usage < 70% && performance > 25%) {
    performance -= 2.0%  // Gradual reduction
    reset PID controller
}
```

*Increase Performance (Aggressive):*
```
if (fpsError > 5.0 || usage > 85%) {
    adjustment = PID.calculate(targetFps, currentFps)
    performance += adjustment * 0.3  // Damped increase
}
```

*Maintain Performance (Stable):*
```
else {
    reset PID controller  // Clear integral/derivative state
}
```

**Integration Points:**

1. **XServerScreen** → Sets `PowerManager.targetFps` from frame rate limiter
2. **PerformanceHudView** → Updates `PowerManager.currentFps`, `currentCpuUsage`, `currentGpuUsage`
3. **PowerManager** → Creates `PerformanceAutoTuner` with callbacks:
   - `onCpuFrequencyChange(freq)` → Calls `setMinCpuValue()` and `setMaxCpuValue()`
   - `onGpuLevelChange(level)` → Calls `setMinGpuPowerLevel()` and `setMaxGpuPowerLevel()`
4. **PowerProfile** → `enableAutoTuning` flag controls auto-tuner lifecycle

**UI Behavior:**
- When auto-tuning is enabled, manual CPU/GPU controls are hidden
- Auto-tuning toggle is only shown when driver supports both CPU and GPU control
- Profile name changes to "Custom" when auto-tuning is toggled

**Performance Characteristics:**

*Startup Phase (0-10 seconds):*
- PID controllers initialize with zero state
- Performance starts at 50% baseline
- Rapid adjustments as integral term accumulates

*Steady State (10+ seconds):*
- Small oscillations around target FPS (±2 FPS)
- Integral term compensates for steady-state error
- Derivative term prevents overshoot

*Load Changes:*
- Scene complexity increase → FPS drops → PID increases performance
- Scene complexity decrease → FPS stable, usage drops → Gradual performance reduction
- Sudden FPS spike → Derivative term dampens response

**Example Tuning Session (Simplified/Illustrative):**
```
[0s]  Target: 60 FPS, Current: 45 FPS, CPU usage: 50%, GPU usage: 50%
      → Large FPS error (15.0 > 5.0 threshold)
      → PID calculates adjustment, applies with decay factor (0.3)
      → CPU perf: 50% → 52%, GPU perf: 50% → 52%

[2s]  Target: 60 FPS, Current: 54 FPS, CPU usage: 88%, GPU usage: 87%
      → FPS error = 6.0 (> 5.0) OR CPU usage > 85%
      → PID continues adjustment with integral accumulation
      → CPU perf: 52% → 54%, GPU perf: 52% → 54%

[4s]  Target: 60 FPS, Current: 59 FPS, CPU usage: 78%, GPU usage: 75%
      → FPS error = 1.0, usage between thresholds (70%-85%)
      → Maintain current performance, reset PID
      → CPU perf: 54% (unchanged), GPU perf: 54% (unchanged)

[6s]  Target: 60 FPS, Current: 60 FPS, CPU usage: 65%, GPU usage: 60%
      → FPS stable (error < 2.0), usage below 70% threshold
      → Gradual reduction (-2% step)
      → CPU perf: 54% → 52%, GPU perf: 54% → 52%
```
*Note: Actual PID calculations use Kp=0.5, Ki=0.2, Kd=0.1 with ADJUSTMENT_DECAY_FACTOR=0.3.
Enable verbose logging to see exact P/I/D terms and outputs.*

**Logging:**
- Enable verbose logging via `PerformanceAutoTuner(enableLogging = true)`
- Logs PID calculations: error, P/I/D terms, output
- Logs tuning decisions: FPS, usage, performance adjustments
- Logs frequency/level changes applied to hardware

## File Structure

```
powercontrol/
├── autotuning/
│   ├── PerformanceAutoTuner.kt        # Automatic performance tuner
│   └── PidController.kt               # PID controller implementation
├── drivers/
│   ├── PerformanceDriver.kt           # Abstract base class
│   ├── PServerDriver.kt               # PServer implementation
│   ├── SamsungPerformanceDriver.kt    # Samsung SDK implementation
│   └── NoOpPerformanceDriver.kt       # No-op fallback implementation
├── profiles/
│   ├── CpuGovernor.kt                 # CPU governor enum
│   └── PerformancePreset.kt           # Performance preset enum
├── PowerManager.kt                    # High-level facade
├── PowerProfile.kt                    # Profile data class and factory
└── README.md                          # This file
```

## References

### Acknowledgments

This implementation was inspired by and references the following projects:

- [GameMode](https://github.com/FeralInteractive/gamemode) - Linux daemon for optimizing system performance on demand (by Feral Interactive)
  - Policy-based CPU control approach
  - Sysfs validation techniques
  - Robust error handling patterns
- [ClusterTune](https://github.com/AurelioB/ClusterTune) - CPU frequency and governor control for Android devices
- [Pulse](https://github.com/keiretrogaming/pulse) - Performance tuning for handheld gaming devices

### Additional Resources

- [Samsung Performance SDK - Overview](https://developer.samsung.com/galaxy-performance/overview.html)
- [Samsung Performance SDK - Programming Guide](https://developer.samsung.com/galaxy-performance/programming-guide.html)
- [Samsung Performance SDK - API Reference](https://developer.samsung.com/galaxy-performance/api-reference)
- PServer: Custom binder service on AYN Odin, Retroid Pocket devices
- [Linux CPU Frequency Scaling](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html)
