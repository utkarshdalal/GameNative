package app.gamenative.utils

import android.content.Context
import androidx.compose.ui.graphics.Color
import app.gamenative.PrefManager
import app.gamenative.R
import com.winlator.box86_64.Box86_64PresetManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.contents.AdrenotoolsManager
import com.winlator.core.KeyValueSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for fetching best configurations for games from GameNative API.
 */
object BestConfigService {
    private const val API_BASE_URL = "https://gamenative-best-config-worker.gamenative.workers.dev/api/best-config"
    private const val TIMEOUT_SECONDS = 10L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // In-memory cache keyed by "${gameName}_${gpuName}"
    private val cache = ConcurrentHashMap<String, BestConfigResponse>()

    /**
     * Data class for API response.
     */
    data class BestConfigResponse(
        val bestConfig: JsonObject,
        val matchType: String, // "exact_gpu_match" | "gpu_family_match" | "fallback_match" | "no_match"
        val matchedGpu: String,
        val matchedDeviceId: Int
    )

    /**
     * Compatibility message with text and color.
     */
    data class CompatibilityMessage(
        val text: String,
        val color: Color
    )

    /**
     * Fetches best configuration for a game.
     * Returns cached response if available, otherwise makes API call.
     */
    suspend fun fetchBestConfig(
        gameName: String,
        gpuName: String
    ): BestConfigResponse? = withContext(Dispatchers.IO) {
        val cacheKey = "${gameName}_${gpuName}"

        // Check cache first
        cache[cacheKey]?.let {
            Timber.tag("BestConfigService").d("Using cached config for $cacheKey")
            return@withContext it
        }

        try {
            withTimeout(TIMEOUT_SECONDS * 1000) {
                val requestBody = JSONObject().apply {
                    put("gameName", gameName)
                    put("gpuName", gpuName)
                }

                val mediaType = "application/json".toMediaType()
                val body = requestBody.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(API_BASE_URL)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Timber.tag("BestConfigService")
                        .w("API request failed - HTTP ${response.code}")
                    return@withTimeout null
                }

                val responseBody = response.body?.string() ?: return@withTimeout null
                val jsonResponse = JSONObject(responseBody)

                val bestConfigJson = jsonResponse.getJSONObject("bestConfig")
                val bestConfig = Json.parseToJsonElement(bestConfigJson.toString()).jsonObject

                val bestConfigResponse = BestConfigResponse(
                    bestConfig = bestConfig,
                    matchType = jsonResponse.getString("matchType"),
                    matchedGpu = jsonResponse.getString("matchedGpu"),
                    matchedDeviceId = jsonResponse.getInt("matchedDeviceId")
                )

                // Cache the response
                cache[cacheKey] = bestConfigResponse

                Timber.tag("BestConfigService")
                    .d("Fetched best config for $gameName on $gpuName (matchType: ${bestConfigResponse.matchType})")

                bestConfigResponse
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Timber.tag("BestConfigService")
                .e(e, "Timeout while fetching best config")
            null
        } catch (e: Exception) {
            Timber.tag("BestConfigService")
                .e(e, "Error fetching best config: ${e.message}")
            null
        }
    }

    /**
     * Gets user-friendly compatibility message based on match type.
     */
    fun getCompatibilityMessage(matchType: String?): CompatibilityMessage {
        return when (matchType) {
            "exact_gpu_match" -> CompatibilityMessage(
                text = "Works on your GPU",
                color = Color(0xFF4CAF50) // Green
            )
            "gpu_family_match" -> CompatibilityMessage(
                text = "Works on your GPU family",
                color = Color(0xFF4CAF50) // Green
            )
            "fallback_match" -> CompatibilityMessage(
                text = "Works on other devices",
                color = Color(0xFFFFC107) // Yellow
            )
            else -> CompatibilityMessage(
                text = "Compatibility unknown",
                color = Color(0xFF9E9E9E) // Grey
            )
        }
    }

    /**
     * Filters config JSON based on match type.
     * For fallback_match, excludes containerVariant, graphicsDriver, dxwrapper, and dxwrapperConfig.
     */
    fun filterConfigByMatchType(config: JsonObject, matchType: String): JsonObject {
        if (matchType == "exact_gpu_match" || matchType == "gpu_family_match") {
            // Apply all fields
            return config
        }

        if (matchType == "fallback_match") {
            // Exclude containerVariant, graphicsDriver, dxwrapper, dxwrapperConfig
            val filtered = config.toMutableMap()
            filtered.remove("graphicsDriver")
            filtered.remove("graphicsDriverVersion")
            filtered.remove("graphicsDriverConfig")
            filtered.remove("dxwrapper")
            filtered.remove("dxwrapperConfig")
            return JsonObject(filtered)
        }

        // For no_match or unknown, return empty config
        return JsonObject(emptyMap())
    }

    /**
     * Validates and fixes component versions in the ContainerData.
     * Checks if versions exist in resource arrays and falls back to PrefManager defaults if not.
     */
    private fun validateComponentVersions(context: Context, config: ContainerData): ContainerData {
        var validatedConfig = config

        // Get resource arrays (same as ContainerConfigDialog)
        val dxvkVersions = context.resources.getStringArray(R.array.dxvk_version_entries).toList()
        val vkd3dVersions = context.resources.getStringArray(R.array.vkd3d_version_entries).toList()
        val box64Versions = context.resources.getStringArray(R.array.box64_version_entries).toList()
        val box64BionicVersions = context.resources.getStringArray(R.array.box64_bionic_version_entries).toList()
        val wowBox64Versions = context.resources.getStringArray(R.array.wowbox64_version_entries).toList()
        val fexcoreVersions = context.resources.getStringArray(R.array.fexcore_version_entries).toList()
        val bionicWineEntries = context.resources.getStringArray(R.array.bionic_wine_entries).toList()
        val glibcWineEntries = context.resources.getStringArray(R.array.glibc_wine_entries).toList()

        // Helper to extract version from display string (e.g., "0.3.6 (Default)" -> "0.3.6")
        fun extractVersion(display: String): String = display.split(" ").first().trim()

        // Helper to check if version exists in list
        fun versionExists(version: String, available: List<String>): Boolean {
            if (version.isEmpty()) return false
            val normalizedVersion = version.trim()
            return available.any {
                extractVersion(it).trim().equals(normalizedVersion, ignoreCase = true) ||
                extractVersion(it).trim().contains(normalizedVersion, ignoreCase = true) ||
                normalizedVersion.contains(extractVersion(it).trim(), ignoreCase = true)
            }
        }

        // Validate DXVK version
        if (config.dxwrapper == "dxvk" && config.dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(config.dxwrapperConfig)
            val version = kvs.get("version")
            if (version.isNotEmpty() && !versionExists(version, dxvkVersions)) {
                Timber.tag("BestConfigService").w("DXVK version $version not found, using PrefManager default")
                validatedConfig = validatedConfig.copy(dxwrapperConfig = PrefManager.dxWrapperConfig)
            }
        }

        // Validate VKD3D version
        if (config.dxwrapper == "vkd3d" && config.dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(config.dxwrapperConfig)
            val version = kvs.get("vkd3dVersion")
            if (version.isNotEmpty() && !versionExists(version, vkd3dVersions)) {
                Timber.tag("BestConfigService").w("VKD3D version $version not found, using PrefManager default")
                validatedConfig = validatedConfig.copy(dxwrapperConfig = PrefManager.dxWrapperConfig)
            }
        }

        // Validate Box64 version (check separately based on container variant)
        val box64VersionsToCheck = when (config.containerVariant) {
            Container.GLIBC -> box64Versions
            Container.BIONIC -> box64BionicVersions
            else -> box64Versions // Default to glibc
        }
        if (config.box64Version.isNotEmpty() && !versionExists(config.box64Version, box64VersionsToCheck)) {
            Timber.tag("BestConfigService").w("Box64 version ${config.box64Version} not found for ${config.containerVariant} variant, using PrefManager default")
            validatedConfig = validatedConfig.copy(box64Version = PrefManager.box64Version)
        }

        // Validate WoWBox64 version (if wineVersion contains arm64ec)
        if (config.wineVersion.contains("arm64ec", ignoreCase = true)) {
            if (config.box64Version.isNotEmpty() && !versionExists(config.box64Version, wowBox64Versions)) {
                Timber.tag("BestConfigService").w("WoWBox64 version ${config.box64Version} not found, using PrefManager default")
                validatedConfig = validatedConfig.copy(box64Version = PrefManager.box64Version)
            }
        }

        // Validate FEXCore version
        if (config.fexcoreVersion.isNotEmpty() && !versionExists(config.fexcoreVersion, fexcoreVersions)) {
            Timber.tag("BestConfigService").w("FEXCore version ${config.fexcoreVersion} not found, using PrefManager default")
            validatedConfig = validatedConfig.copy(fexcoreVersion = PrefManager.fexcoreVersion)
        }

        // Validate Wine/Proton version
        val allWineVersions = (bionicWineEntries + glibcWineEntries).distinct()
        if (config.wineVersion.isNotEmpty() && !versionExists(config.wineVersion, allWineVersions)) {
            Timber.tag("BestConfigService").w("Wine version ${config.wineVersion} not found, using PrefManager default")
            validatedConfig = validatedConfig.copy(wineVersion = PrefManager.wineVersion)
        }

        // Validate graphics driver version (from graphicsDriverConfig)
        if (config.graphicsDriverConfig.isNotEmpty()) {
            val configMap = config.graphicsDriverConfig.split(";").associate { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) kv[0] to kv[1] else part to ""
            }
            val driverVersion = configMap["version"] ?: ""

            if (driverVersion.isNotEmpty()) {
                val availableVersions = if (config.containerVariant == Container.BIONIC) {
                    // For bionic containers, check against wrapper_graphics_driver_version_entries
                    context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries).toList()
                } else {
                    // For glibc containers, check based on driver type
                    when {
                        config.graphicsDriver.contains("turnip", ignoreCase = true) ->
                            context.resources.getStringArray(R.array.turnip_version_entries).toList()
                        config.graphicsDriver.contains("virgl", ignoreCase = true) ->
                            context.resources.getStringArray(R.array.virgl_version_entries).toList()
                        config.graphicsDriver.contains("vortek", ignoreCase = true) ->
                            context.resources.getStringArray(R.array.vortek_version_entries).toList()
                        config.graphicsDriver.contains("adreno", ignoreCase = true) -> {
                            val base = context.resources.getStringArray(R.array.adreno_version_entries).toList()
                            try {
                                (base + AdrenotoolsManager(context).enumarateInstalledDrivers()).distinct()
                            } catch (e: Exception) {
                                base
                            }
                        }
                        config.graphicsDriver.contains("sd-8-elite", ignoreCase = true) ->
                            context.resources.getStringArray(R.array.sd8elite_version_entries).toList()
                        else ->
                            context.resources.getStringArray(R.array.zink_version_entries).toList()
                    }
                }

                if (!versionExists(driverVersion, availableVersions)) {
                    Timber.tag("BestConfigService").w("Graphics driver version $driverVersion not found for ${config.containerVariant} variant, using PrefManager default")
                    validatedConfig = validatedConfig.copy(graphicsDriverConfig = PrefManager.graphicsDriverConfig)
                }
            }
        }

        // Validate Box64 preset
        val box64Preset = Box86_64PresetManager.getPreset("box64", context, config.box64Preset)
        if (box64Preset == null) {
            Timber.tag("BestConfigService").w("Box64 preset ${config.box64Preset} not found, using PrefManager default")
            validatedConfig = validatedConfig.copy(box64Preset = PrefManager.box64Preset)
        }

        // Validate Box86 preset
        val box86Preset = Box86_64PresetManager.getPreset("box86", context, config.box86Preset)
        if (box86Preset == null) {
            Timber.tag("BestConfigService").w("Box86 preset ${config.box86Preset} not found, using PrefManager default")
            validatedConfig = validatedConfig.copy(box86Preset = PrefManager.box86Preset)
        }

        return validatedConfig
    }

    /**
     * Parses bestConfig JSON into ContainerData.
     * First parses values (using PrefManager defaults), then validates component versions.
     */
    fun parseConfigToContainerData(context: Context, configJson: JsonObject, matchType: String): ContainerData? {
        try {
            // Step 1: Filter config based on match type
            val filteredConfig = filterConfigByMatchType(configJson, matchType)
            val filteredJson = JSONObject(filteredConfig.toString())

            // Step 2: Parse values into ContainerData (using PrefManager defaults)
            var config = ContainerData(
                envVars = filteredJson.optString("envVars", PrefManager.envVars),
                graphicsDriver = filteredJson.optString("graphicsDriver", PrefManager.graphicsDriver),
                graphicsDriverVersion = filteredJson.optString("graphicsDriverVersion", PrefManager.graphicsDriverVersion),
                graphicsDriverConfig = filteredJson.optString("graphicsDriverConfig", PrefManager.graphicsDriverConfig),
                dxwrapper = filteredJson.optString("dxwrapper", PrefManager.dxWrapper),
                dxwrapperConfig = filteredJson.optString("dxwrapperConfig", PrefManager.dxWrapperConfig),
                audioDriver = filteredJson.optString("audioDriver", PrefManager.audioDriver),
                wincomponents = filteredJson.optString("wincomponents", PrefManager.winComponents),
                execArgs = filteredJson.optString("execArgs", PrefManager.execArgs),
                launchRealSteam = filteredJson.optBoolean("launchRealSteam", PrefManager.launchRealSteam),
                steamType = filteredJson.optString("steamType", "normal"),
                cpuList = filteredJson.optString("cpuList", PrefManager.cpuList),
                cpuListWoW64 = filteredJson.optString("cpuListWoW64", PrefManager.cpuListWoW64),
                wow64Mode = filteredJson.optBoolean("wow64Mode", PrefManager.wow64Mode),
                startupSelection = filteredJson.optInt("startupSelection", PrefManager.startupSelection).toByte(),
                box86Version = filteredJson.optString("box86Version", PrefManager.box86Version),
                box64Version = filteredJson.optString("box64Version", PrefManager.box64Version),
                box86Preset = filteredJson.optString("box86Preset", PrefManager.box86Preset),
                box64Preset = filteredJson.optString("box64Preset", PrefManager.box64Preset),
                desktopTheme = filteredJson.optString("desktopTheme", ""),
                containerVariant = filteredJson.optString("containerVariant", PrefManager.containerVariant),
                wineVersion = filteredJson.optString("wineVersion", PrefManager.wineVersion),
                emulator = filteredJson.optString("emulator", PrefManager.emulator),
                fexcoreVersion = filteredJson.optString("fexcoreVersion", PrefManager.fexcoreVersion),
                fexcoreTSOMode = filteredJson.optString("fexcoreTSOMode", PrefManager.fexcoreTSOMode),
                fexcoreX87Mode = filteredJson.optString("fexcoreX87Mode", PrefManager.fexcoreX87Mode),
                fexcoreMultiBlock = filteredJson.optString("fexcoreMultiBlock", PrefManager.fexcoreMultiBlock),
                renderer = filteredJson.optString("renderer", PrefManager.renderer),
                csmt = filteredJson.optBoolean("csmt", PrefManager.csmt),
                videoPciDeviceID = filteredJson.optInt("videoPciDeviceID", PrefManager.videoPciDeviceID),
                offScreenRenderingMode = filteredJson.optString("offScreenRenderingMode", PrefManager.offScreenRenderingMode),
                strictShaderMath = filteredJson.optBoolean("strictShaderMath", PrefManager.strictShaderMath),
                useDRI3 = filteredJson.optBoolean("useDRI3", PrefManager.useDRI3),
                videoMemorySize = filteredJson.optString("videoMemorySize", PrefManager.videoMemorySize),
                mouseWarpOverride = filteredJson.optString("mouseWarpOverride", PrefManager.mouseWarpOverride),
                sdlControllerAPI = filteredJson.optBoolean("sdlControllerAPI", true),
                enableXInput = filteredJson.optBoolean("enableXInput", PrefManager.xinputEnabled),
                enableDInput = filteredJson.optBoolean("enableDInput", PrefManager.dinputEnabled),
                dinputMapperType = filteredJson.optInt("dinputMapperType", PrefManager.dinputMapperType).toByte(),
                disableMouseInput = filteredJson.optBoolean("disableMouseInput", PrefManager.disableMouseInput),
                touchscreenMode = filteredJson.optBoolean("touchscreenMode", false),
                language = filteredJson.optString("language", PrefManager.containerLanguage),
                emulateKeyboardMouse = filteredJson.optBoolean("emulateKeyboardMouse", false),
                forceDlc = filteredJson.optBoolean("forceDlc", PrefManager.forceDlc),
                useLegacyDRM = filteredJson.optBoolean("useLegacyDRM", PrefManager.useLegacyDRM),
                sharpnessEffect = filteredJson.optString("sharpnessEffect", PrefManager.sharpnessEffect),
                sharpnessLevel = filteredJson.optInt("sharpnessLevel", PrefManager.sharpnessLevel),
                sharpnessDenoise = filteredJson.optInt("sharpnessDenoise", PrefManager.sharpnessDenoise),
            )

            // Step 3: Validate component versions against resource arrays
            config = validateComponentVersions(context, config)

            return config
        } catch (e: Exception) {
            Timber.tag("BestConfigService").e(e, "Failed to parse config to ContainerData: ${e.message}")
            return null
        }
    }
}

