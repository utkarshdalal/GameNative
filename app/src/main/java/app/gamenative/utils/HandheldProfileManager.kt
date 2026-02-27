package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.PrefManager
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.Container
import com.winlator.core.DefaultVersion
import com.winlator.core.GPUInformation
import com.winlator.fexcore.FEXCorePreset
import timber.log.Timber

/**
 * Auto-configuration for Android gaming handhelds and devices.
 * Detects device hardware (SoC, GPU, model) and applies optimal Wine/emulation
 * defaults so games from Steam, GOG, and Epic launch with minimal user configuration.
 *
 * Device tiers:
 *  FLAGSHIP_ELITE  - SD 8 Elite / 8 Gen 3, Adreno 830+
 *  FLAGSHIP        - SD 8 Gen 2 / 8 Gen 1, Adreno 730-750
 *  HIGH            - SD 7xx / 888 / 870, Adreno 710-732
 *  MID             - SD 865 / 6xx series, Adreno 610-660
 *  LOW             - Non-Adreno (Mali, PowerVR, etc.)
 */
object HandheldProfileManager {

    private const val TAG = "HandheldProfile"

    enum class DeviceTier {
        FLAGSHIP_ELITE,
        FLAGSHIP,
        HIGH,
        MID,
        LOW,
    }

    data class DeviceProfile(
        val tier: DeviceTier,
        val name: String,
        // DefaultVersion overrides (set every launch - volatile statics)
        val variant: String = Container.BIONIC,
        val wineVersion: String = "proton-9.0-arm64ec",
        val graphicsDriver: String = "Wrapper",
        val wrapper: String,
        val dxvk: String,
        val vkd3d: String = "2.14.1",
        val steamType: String = Container.STEAM_TYPE_NORMAL,
        val asyncCache: String = "1",
        // PrefManager overrides (set once per device - persisted)
        val emulator: String = "FEXCore",
        val fexcorePreset: String = FEXCorePreset.INTERMEDIATE,
        val fexcoreTSOMode: String = "Fast",
        val fexcoreX87Mode: String = "Fast",
        val fexcoreMultiBlock: String = "Disabled",
        val box64Preset: String = Box86_64Preset.COMPATIBILITY,
        val box86Preset: String = Box86_64Preset.COMPATIBILITY,
        val screenSize: String = "1280x720",
        val videoMemorySize: String = "2048",
        val startupSelection: Int = Container.STARTUP_SELECTION_AGGRESSIVE.toInt(),
    )

    // SoC model identifiers (Build.SOC_MODEL, API 31+) mapped to device tier.
    // Covers Snapdragon, some Dimensity. Checked with contains() for flexibility.
    private val SOC_TIERS = linkedMapOf(
        // --- Snapdragon 8 Elite / 8 Gen 3 ---
        "SM8750" to DeviceTier.FLAGSHIP_ELITE,   // SD 8 Elite
        "SM8650" to DeviceTier.FLAGSHIP_ELITE,   // SD 8 Gen 3
        "pineapple" to DeviceTier.FLAGSHIP_ELITE, // SD 8 Gen 3 codename
        "SG8375" to DeviceTier.FLAGSHIP_ELITE,   // SD G3x Gen 3 (gaming)
        // --- Snapdragon 8 Gen 2 / 8 Gen 1 ---
        "SM8550" to DeviceTier.FLAGSHIP,          // SD 8 Gen 2
        "kalama" to DeviceTier.FLAGSHIP,          // SD 8 Gen 2 codename
        "SM8475" to DeviceTier.FLAGSHIP,          // SD 8+ Gen 1
        "SM8450" to DeviceTier.FLAGSHIP,          // SD 8 Gen 1
        "taro" to DeviceTier.FLAGSHIP,            // SD 8 Gen 1 codename
        "SG8275" to DeviceTier.FLAGSHIP,          // SD G3x Gen 2 (gaming)
        // --- Snapdragon 7xx / 888 / 870 ---
        "SM7675" to DeviceTier.HIGH,              // SD 7+ Gen 3
        "SM7550" to DeviceTier.HIGH,              // SD 7 Gen 3
        "SM7435" to DeviceTier.HIGH,              // SD 7s Gen 2
        "SM8350" to DeviceTier.HIGH,              // SD 888
        "lahaina" to DeviceTier.HIGH,             // SD 888 codename
        // --- Snapdragon 865 / 860 / 855 ---
        "kona" to DeviceTier.MID,                 // SD 865 codename
        "msmnile" to DeviceTier.MID,              // SD 855 codename
        "SM8250" to DeviceTier.MID,               // SD 870/865
        "SM8150" to DeviceTier.MID,               // SD 855
    )

    // Known gaming handheld manufacturers -> model prefix -> friendly name.
    // Used for logging and future per-device overrides.
    private val KNOWN_HANDHELDS = mapOf(
        "ayn" to listOf(
            "Odin 3" to "AYN Odin 3",
            "Odin2 Max" to "AYN Odin 2 Max",
            "Odin2 Mini" to "AYN Odin 2 Mini",
            "Odin2" to "AYN Odin 2",
        ),
        "ayaneo" to listOf(
            "POCKET S2" to "AYANEO Pocket S2",
            "POCKET EVO" to "AYANEO Pocket EVO",
            "POCKET MICRO" to "AYANEO Pocket Micro",
            "POCKET S" to "AYANEO Pocket S",
        ),
        "retroid" to listOf(
            "Pocket 5" to "Retroid Pocket 5",
            "Pocket Flip 2" to "Retroid Pocket Flip 2",
            "Pocket Mini" to "Retroid Pocket Mini",
            "Pocket 4 Pro" to "Retroid Pocket 4 Pro",
            "Pocket 4" to "Retroid Pocket 4",
        ),
        "gpd" to listOf(
            "XP3" to "GPD XP3",
            "XP2" to "GPD XP2",
            "XP Plus" to "GPD XP Plus",
        ),
    )

    /**
     * Detects the device hardware and applies the optimal configuration profile.
     * Called from [ContainerUtils.setContainerDefaults] on every app launch.
     *
     * - DefaultVersion statics are always updated (they reset on process death).
     * - PrefManager settings are only written on first detection for a given device,
     *   so user customizations made in the UI are preserved across restarts.
     */
    fun detectAndApply(context: Context) {
        val tier = detectDeviceTier(context)
        val profile = buildProfile(tier, context)
        val handheldName = detectHandheldName()
        val label = handheldName ?: profile.name

        // Always set volatile DefaultVersion statics
        applyDefaultVersion(profile)

        // Only set PrefManager defaults once per device to avoid overwriting user settings.
        // The key encodes the tier + SoC so a device change triggers re-application.
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "unknown"
        val profileKey = "${tier.name}_${soc}"
        if (PrefManager.deviceProfileKey != profileKey) {
            applyPrefManagerDefaults(profile)
            PrefManager.deviceProfileKey = profileKey
            Timber.tag(TAG).i("First-time profile applied: $label (tier=$tier, soc=$soc)")
        } else {
            Timber.tag(TAG).d("Profile already applied for: $label (tier=$tier)")
        }
    }

    /**
     * Detects device tier. Priority: SoC model (API 31+) > GPU renderer string.
     */
    private fun detectDeviceTier(context: Context): DeviceTier {
        // 1. SoC model matching (most reliable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            if (!soc.isNullOrEmpty()) {
                for ((pattern, tier) in SOC_TIERS) {
                    if (soc.contains(pattern, ignoreCase = true)) {
                        Timber.tag(TAG).d("SoC match: $soc contains '$pattern' -> $tier")
                        return tier
                    }
                }
            }
        }

        // 2. GPU renderer fallback
        return when {
            GPUInformation.isAdreno8Elite(context) -> DeviceTier.FLAGSHIP_ELITE
            GPUInformation.isTurnipCapable(context) && !GPUInformation.isAdreno6xx(context)
                && !GPUInformation.isAdreno710_720_732(context) -> DeviceTier.FLAGSHIP
            GPUInformation.isAdreno710_720_732(context) -> DeviceTier.HIGH
            GPUInformation.isAdreno6xx(context) -> DeviceTier.MID
            GPUInformation.isTurnipCapable(context) -> DeviceTier.MID
            else -> DeviceTier.LOW
        }
    }

    /**
     * Checks if this device is a known gaming handheld by manufacturer + model.
     */
    private fun detectHandheldName(): String? {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return null
        val model = Build.MODEL ?: return null
        val entries = KNOWN_HANDHELDS[mfr] ?: return null
        for ((prefix, name) in entries) {
            if (model.startsWith(prefix, ignoreCase = true)) {
                return name
            }
        }
        return null
    }

    /**
     * Builds the optimal [DeviceProfile] for the given [DeviceTier].
     * GPU-specific adjustments (e.g. DXVK version for Adreno 710/720/732) are applied here.
     */
    private fun buildProfile(tier: DeviceTier, context: Context): DeviceProfile = when (tier) {
        DeviceTier.FLAGSHIP_ELITE -> DeviceProfile(
            tier = tier,
            name = "Flagship Elite (8 Elite / 8 Gen 3)",
            wrapper = "Turnip_Gen8_V23",
            dxvk = "2.4.1-gplasync",
            steamType = Container.STEAM_TYPE_NORMAL,
            fexcorePreset = FEXCorePreset.PERFORMANCE,
            fexcoreMultiBlock = "Enabled",
            box64Preset = Box86_64Preset.PERFORMANCE,
            box86Preset = Box86_64Preset.PERFORMANCE,
            screenSize = "1280x720",
            videoMemorySize = "4096",
            startupSelection = Container.STARTUP_SELECTION_AGGRESSIVE.toInt(),
        )
        DeviceTier.FLAGSHIP -> DeviceProfile(
            tier = tier,
            name = "Flagship (8 Gen 2 / 8 Gen 1)",
            wrapper = "turnip26.0.0_R8",
            dxvk = "2.4.1-gplasync",
            steamType = Container.STEAM_TYPE_NORMAL,
            fexcorePreset = FEXCorePreset.INTERMEDIATE,
            fexcoreMultiBlock = "Enabled",
            box64Preset = Box86_64Preset.INTERMEDIATE,
            box86Preset = Box86_64Preset.INTERMEDIATE,
            screenSize = "1280x720",
            videoMemorySize = "3072",
            startupSelection = Container.STARTUP_SELECTION_AGGRESSIVE.toInt(),
        )
        DeviceTier.HIGH -> DeviceProfile(
            tier = tier,
            name = "High (7xx / 888 / 870)",
            wrapper = "turnip26.0.0_R8",
            // Adreno 6xx and 710/720/732 GPUs benefit from older DXVK for stability.
            // SD 888 (SoC=HIGH) has Adreno 660 (6xx), so check actual GPU not just tier.
            dxvk = if (GPUInformation.isAdreno6xx(context) || GPUInformation.isAdreno710_720_732(context))
                "1.11.1-sarek" else "2.4.1-gplasync",
            steamType = Container.STEAM_TYPE_LIGHT,
            fexcorePreset = FEXCorePreset.INTERMEDIATE,
            fexcoreMultiBlock = "Disabled",
            box64Preset = Box86_64Preset.COMPATIBILITY,
            box86Preset = Box86_64Preset.COMPATIBILITY,
            screenSize = "1280x720",
            videoMemorySize = "2048",
            startupSelection = Container.STARTUP_SELECTION_ESSENTIAL.toInt(),
        )
        DeviceTier.MID -> DeviceProfile(
            tier = tier,
            name = "Mid (6xx / 865)",
            wrapper = "turnip26.0.0_R8",
            dxvk = "1.11.1-sarek",
            steamType = Container.STEAM_TYPE_LIGHT,
            fexcorePreset = FEXCorePreset.COMPATIBILITY,
            fexcoreMultiBlock = "Disabled",
            box64Preset = Box86_64Preset.COMPATIBILITY,
            box86Preset = Box86_64Preset.COMPATIBILITY,
            screenSize = "960x540",
            videoMemorySize = "2048",
            startupSelection = Container.STARTUP_SELECTION_ESSENTIAL.toInt(),
        )
        DeviceTier.LOW -> DeviceProfile(
            tier = tier,
            name = "Low (Non-Adreno)",
            wrapper = "System",
            dxvk = "async-1.10.3",
            asyncCache = "0",
            steamType = Container.STEAM_TYPE_LIGHT,
            fexcorePreset = FEXCorePreset.COMPATIBILITY,
            fexcoreMultiBlock = "Disabled",
            box64Preset = Box86_64Preset.STABILITY,
            box86Preset = Box86_64Preset.STABILITY,
            screenSize = "960x540",
            videoMemorySize = "1024",
            startupSelection = Container.STARTUP_SELECTION_NORMAL.toInt(),
        )
    }

    /**
     * Sets DefaultVersion statics. These are volatile (reset on process death)
     * and must be applied on every app launch before any Container class usage.
     */
    private fun applyDefaultVersion(profile: DeviceProfile) {
        DefaultVersion.VARIANT = profile.variant
        DefaultVersion.WINE_VERSION = profile.wineVersion
        DefaultVersion.DEFAULT_GRAPHICS_DRIVER = profile.graphicsDriver
        DefaultVersion.DXVK = profile.dxvk
        DefaultVersion.VKD3D = profile.vkd3d
        DefaultVersion.WRAPPER = profile.wrapper
        DefaultVersion.STEAM_TYPE = profile.steamType
        DefaultVersion.ASYNC_CACHE = profile.asyncCache
    }

    /**
     * Sets PrefManager defaults for emulation settings not covered by DefaultVersion.
     * Only called once per device (guarded by [PrefManager.deviceProfileKey]).
     */
    private fun applyPrefManagerDefaults(profile: DeviceProfile) {
        PrefManager.emulator = profile.emulator
        PrefManager.fexcorePreset = profile.fexcorePreset
        PrefManager.fexcoreTSOMode = profile.fexcoreTSOMode
        PrefManager.fexcoreX87Mode = profile.fexcoreX87Mode
        PrefManager.fexcoreMultiBlock = profile.fexcoreMultiBlock
        PrefManager.box64Preset = profile.box64Preset
        PrefManager.box86Preset = profile.box86Preset
        PrefManager.screenSize = profile.screenSize
        PrefManager.videoMemorySize = profile.videoMemorySize
        PrefManager.startupSelection = profile.startupSelection
    }
}
