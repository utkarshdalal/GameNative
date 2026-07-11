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
   - Maintains backward compatibility with existing UI code

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
- ✅ `start()` - No-op (PServer doesn't require initialization)
- ✅ `stop()` - Restores CPU governor to first available governor, then restores all modified sysfs files to 644 permissions using concatenated chmod commands (runs asynchronously on background thread)

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
- ✅ `start()` - No-op (performance controls started by individual setters via `performanceManager.start(params)`)
- ✅ `stop()` - Calls `performanceManager.stop()` to stop all active performance controls

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

## Driver Selection

PowerManager automatically selects the appropriate driver during initialization:

1. **Samsung Performance SDK** - Checked first for Samsung devices
2. **PServer** - Checked for AYN Odin, Retroid Pocket devices
3. **Fallback** - Uses NoOpPerformanceDriver (no functionality)

The selection happens in `PowerManager.initialize(context)` which should be called during app startup.

## Driver Lifecycle

Drivers follow a game lifecycle pattern:

1. **Game Environment Setup** (`XServerScreen.kt`)
   - After `PluviaApp.xEnvironment` is initialized
   - `PowerManager.start()` is called
   - Driver-specific initialization occurs

2. **Game Running**
   - User can adjust performance settings via UI
   - Each setting change calls the appropriate driver method
   - PServerDriver: Writes to sysfs and sets files to 444 (read-only)
   - SamsungPerformanceDriver: Calls `performanceManager.start(params)` with new settings

3. **Game Environment Shutdown** (`PluviaApp.shutdownEnvironment()`)
   - `PowerManager.stop()` is called
   - PServerDriver: Restores CPU governor to first available governor, then restores all modified sysfs files to 644 permissions
   - SamsungPerformanceDriver: Calls `performanceManager.stop()` to stop all performance controls

## Adding New Drivers

To add support for a new device:

1. Create a new driver class in `drivers/` folder extending `PerformanceDriver`
2. Implement all abstract methods according to device capabilities
3. Update `PowerManager.initialize()` to include the new driver in the selection logic:

```kotlin
fun initialize(context: Context) {
    driver = when {
        NewDriver(context).isDriverSupported() -> NewDriver(context)
        SamsungPerformanceDriver(context).isDriverSupported() -> SamsungPerformanceDriver(context)
        PServerDriver().isDriverSupported() -> PServerDriver()
        else -> PServerDriver() // Fallback
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

- [ClusterTune](https://github.com/AurelioB/ClusterTune) - CPU frequency and governor control for Android devices
- [Pulse](https://github.com/keiretrogaming/pulse) - Performance tuning for handheld gaming devices

### Additional Resources

- [Samsung Performance SDK - Overview](https://developer.samsung.com/galaxy-performance/overview.html)
- [Samsung Performance SDK - Programming Guide](https://developer.samsung.com/galaxy-performance/programming-guide.html)
- [Samsung Performance SDK - API Reference](https://developer.samsung.com/galaxy-performance/api-reference)
- PServer: Custom binder service on AYN Odin, Retroid Pocket devices
- [Linux CPU Frequency Scaling](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html)
