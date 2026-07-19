# Performance Control Architecture for GameNative

## Overview

GameNative's performance control system provides CPU and GPU tuning capabilities for Android gaming devices. It supports multiple device types through an extensible driver architecture, including PServer-based devices (AYN Odin, Retroid Pocket) and Samsung Galaxy devices via the Samsung Performance SDK. The system allows users to adjust CPU governors, frequency scaling, and GPU performance levels to optimize game performance.

## Architecture

### Core Components

1. **PerformanceDriver** (Abstract Base Class)
   - Location: `drivers/PerformanceDriver.kt`
   - Defines the interface for all device-specific performance drivers
   - Provides common functionality like frequency formatting
   - Declares abstract methods for:
     - `isDriverSupported()` - Driver availability detection
     - `isGovernorSupported()` - CPU governor control support
     - `isGpuSupported()` - GPU control support
     - `isFanSupported()` - Fan control support (future)
     - `start()` - Initialize driver when game starts
     - `stop()` - Cleanup driver when game stops
     - `getDefaultProfile()` - Returns default Balanced profile for the device
     - CPU: `getCurrentMinCpuValue()`, `getCurrentMaxCpuValue()`, `getCurrentGovernor()`
     - CPU: `setMinCpuValue(value)`, `setMaxCpuValue(value)`, `setGovernor(governor)`
     - CPU: `getAvailableGovernors()`, `getAvailableCpuFrequencies()`
     - GPU: `getCurrentGpuValue()`, `getAvailableGpuFrequencies()`
     - GPU: `getCurrentMinGpuPowerLevel()`, `getCurrentMaxGpuPowerLevel()`, `getNumGpuPowerLevels()`
     - GPU: `setMinGpuPowerLevel(level)`, `setMaxGpuPowerLevel(level)`

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
   - Maintains backward compatibility with existing UI code

5. **PowerProfile** (Data Class)
   - Location: `PowerProfile.kt`
   - Serializable data class representing a complete performance configuration
   - Fields (all mutable `var`):
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

## Supported Features

### Current (PServerDriver)

**CPU Control:**
- ✅ CPU governor control
- ✅ CPU frequency scaling (min/max)
- ✅ Multiple governor support (schedutil, performance, powersave, etc.)
- ✅ Sysfs file permission management (chmod 444 after write, restore to 644 on stop)

**GPU Control (Adreno):**
- ✅ GPU power level control (min/max power levels)
- ✅ Power level range detection (`num_pwrlevels`)
- ✅ Available GPU frequencies enumeration (read-only)
- ✅ Current GPU frequency monitoring (read-only)
- ✅ Sysfs file permission management (chmod 444 after write, restore to 644 on stop)
- ❌ Direct GPU frequency setting (not supported by hardware)

**Lifecycle:**
- ✅ `start()` - Restores saved profile from preferences (or applies default Balanced profile)
- ✅ `stop()` - **Critical performance restoration**:
  1. Resets CPU frequencies to full range (min to max available)
  2. Resets GPU power levels to full range (0 to max)
  3. Restores CPU governor to first available governor
  4. Restores all modified sysfs files to 644 permissions
  - Runs asynchronously on background thread
  - **Prevents device slowness** when exiting from Power Save mode

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
- ✅ `start()` - Restores saved profile from preferences (or applies default Balanced profile)
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
- Stored in `PrefManager.powerControlProfile` (DataStore preference)
- Includes all profile fields: name, governor, CPU frequencies, GPU power levels

**Lifecycle Integration:**
1. **On `start()`**:
   - Attempts to restore saved profile from preferences
   - If no saved profile exists, applies default Balanced profile from `driver.getDefaultProfile()`
   - Applies the profile to hardware via driver methods

2. **On `stop()`**:
   - Saves `currentProfile` to preferences (if not null)
   - Ensures user's last settings are preserved for next session

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
   - `PowerManager.start()` is called
   - **Profile Restoration**:
     - Attempts to load saved profile from `PrefManager.powerControlProfile`
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
     - Saves `currentProfile` to preferences for next session
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

## File Structure

```
powercontrol/
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
