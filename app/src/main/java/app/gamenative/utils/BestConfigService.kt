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
import kotlinx.serialization.json.JsonPrimitive
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
    fun getCompatibilityMessage(context: Context, matchType: String?): CompatibilityMessage {
        return when (matchType) {
            "exact_gpu_match" -> CompatibilityMessage(
                text = context.getString(R.string.best_config_exact_gpu_match),
                color = Color.Green
            )
            "gpu_family_match" -> CompatibilityMessage(
                text = context.getString(R.string.best_config_gpu_family_match),
                color = Color.Green
            )
            "fallback_match" -> CompatibilityMessage(
                text = context.getString(R.string.best_config_fallback_match),
                color = Color.Yellow
            )
            else -> CompatibilityMessage(
                text = context.getString(R.string.best_config_compatibility_unknown),
                color = Color.Gray
            )
        }
    }

    /**
     * Filters config JSON based on match type.
     * For fallback_match, excludes containerVariant, graphicsDriver, dxwrapper, and dxwrapperConfig.
     */
    fun filterConfigByMatchType(config: JsonObject, matchType: String): JsonObject {
        val filtered = config.toMutableMap()

        if (matchType == "exact_gpu_match" || matchType == "gpu_family_match") {
            // Apply all fields
            return JsonObject(filtered)
        }

        if (matchType == "fallback_match") {
            // Exclude containerVariant, graphicsDriver, dxwrapper, dxwrapperConfig
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
     * Validates component versions in the filtered JSON.
     * Updates invalid versions to PrefManager defaults and continues validation.
     */
    private fun validateComponentVersions(context: Context, filteredJson: JSONObject): Boolean {
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

        // Get values from JSON (only if present)
        val dxwrapper = filteredJson.optString("dxwrapper", "")
        val dxwrapperConfig = filteredJson.optString("dxwrapperConfig", "")
        val containerVariant = filteredJson.optString("containerVariant", "")
        val box64Version = filteredJson.optString("box64Version", "")
        val wineVersion = filteredJson.optString("wineVersion", "")
        val emulator = filteredJson.optString("emulator", "")
        val fexcoreVersion = filteredJson.optString("fexcoreVersion", "")
        val graphicsDriver = filteredJson.optString("graphicsDriver", "")
        val graphicsDriverConfig = filteredJson.optString("graphicsDriverConfig", "")
        val box64Preset = filteredJson.optString("box64Preset", "")

        // Validate DXVK version
        if (dxwrapper == "dxvk" && dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(dxwrapperConfig)
            val version = kvs.get("version")
            if (version.isNotEmpty() && !versionExists(version, dxvkVersions)) {
                Timber.tag("BestConfigService").w("DXVK version $version not found, updating to PrefManager default")
                return false
                filteredJson.put("dxwrapperConfig", PrefManager.dxWrapperConfig)
            }
        }

        // Validate VKD3D version
        if (dxwrapper == "vkd3d" && dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(dxwrapperConfig)
            val version = kvs.get("vkd3dVersion")
            if (version.isNotEmpty() && !versionExists(version, vkd3dVersions)) {
                Timber.tag("BestConfigService").w("VKD3D version $version not found, updating to PrefManager default")
                return false
                filteredJson.put("dxwrapperConfig", PrefManager.dxWrapperConfig)
            }
        }

        // Validate Box64 version (check separately based on container variant)
        // Box64 has different version entries for bionic and glibc containers
        if (box64Version.isNotEmpty() && containerVariant.isNotEmpty()) {
            val box64VersionsToCheck = when {
                containerVariant.equals(Container.BIONIC, ignoreCase = true) -> box64BionicVersions
                containerVariant.equals(Container.GLIBC, ignoreCase = true) -> box64Versions
                else -> {
                    // Default based on container variant, but log warning
                    Timber.tag("BestConfigService").w("Unknown container variant '$containerVariant', defaulting to glibc Box64 versions")
                    box64Versions
                }
            }
            if (!versionExists(box64Version, box64VersionsToCheck)) {
                Timber.tag("BestConfigService").w("Box64 version $box64Version not found in $containerVariant variant entries, updating to PrefManager default")
                return false
                filteredJson.put("box64Version", PrefManager.box64Version)
            }
        }

        // Validate WoWBox64 version (if wineVersion contains arm64ec)
        if (wineVersion.contains("arm64ec", ignoreCase = true)) {
            if (box64Version.isNotEmpty() && !versionExists(box64Version, wowBox64Versions) && emulator != "FEXCore") {
                Timber.tag("BestConfigService").w("WoWBox64 version $box64Version not found, updating to PrefManager default")
                return false
                filteredJson.put("box64Version", PrefManager.box64Version)
            }
        }

        // Validate FEXCore version
        if (fexcoreVersion.isNotEmpty() && !versionExists(fexcoreVersion, fexcoreVersions)) {
            Timber.tag("BestConfigService").w("FEXCore version $fexcoreVersion not found, updating to PrefManager default")
            return false
            filteredJson.put("fexcoreVersion", PrefManager.fexcoreVersion)
        }

        // Validate Wine/Proton version (check separately based on container variant)
        // Wine versions are different for bionic and glibc containers
        if (wineVersion.isNotEmpty() && containerVariant.isNotEmpty()) {
            val wineVersionsToCheck = when {
                containerVariant.equals(Container.BIONIC, ignoreCase = true) -> bionicWineEntries
                containerVariant.equals(Container.GLIBC, ignoreCase = true) -> glibcWineEntries
                else -> {
                    // Default to all versions if variant is unknown
                    Timber.tag("BestConfigService").w("Unknown container variant '$containerVariant', checking against all wine versions")
                    (bionicWineEntries + glibcWineEntries).distinct()
                }
            }
            if (!versionExists(wineVersion, wineVersionsToCheck)) {
                Timber.tag("BestConfigService").w("Wine version $wineVersion not found in $containerVariant variant entries, updating to PrefManager default")
                return false
                filteredJson.put("wineVersion", PrefManager.wineVersion)
            }
        }

        // Validate graphics driver version (from graphicsDriverConfig)
        if (containerVariant.equals(Container.BIONIC, ignoreCase = true) && graphicsDriverConfig.isNotEmpty()) {
            val configMap = graphicsDriverConfig.split(";").associate { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) kv[0] to kv[1] else part to ""
            }
            val driverVersion = configMap["version"] ?: ""

            if (driverVersion.isNotEmpty()) {
                if (containerVariant == Container.BIONIC) {
                    // For bionic containers, check against wrapper_graphics_driver_version_entries
                    val availableVersions = context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries).toList()
                    if (!versionExists(driverVersion, availableVersions)) {
                        Timber.tag("BestConfigService")
                            .w("Graphics driver version $driverVersion not found for $containerVariant variant, updating to PrefManager default")
                        return false
                        filteredJson.put("graphicsDriverConfig", PrefManager.graphicsDriverConfig)
                    }
                }
            }
        }

        // Validate Box64 preset
        if (box64Preset.isNotEmpty()) {
            val preset = Box86_64PresetManager.getPreset("box64", context, box64Preset)
            if (preset == null) {
                Timber.tag("BestConfigService").w("Box64 preset $box64Preset not found, updating to PrefManager default")
                return false
                filteredJson.put("box64Preset", PrefManager.box64Preset)
            }
        }

        return true
    }

    /**
     * Parses bestConfig JSON into a map of fields to update.
     * First parses values (using PrefManager defaults for validation), then validates component versions.
     * Returns map with only fields present in config (no defaults), or empty map if validation fails.
     */
    fun parseConfigToContainerData(context: Context, configJson: JsonObject, matchType: String, applyKnownConfig: Boolean): Map<String, Any?>? {
        try {
            val originalJson = JSONObject(configJson.toString())

            if (!applyKnownConfig){
                val resultMap = mutableMapOf<String, Any?>()
                if (originalJson.has("executablePath") && !originalJson.isNull("executablePath")) {
                    resultMap["executablePath"] = originalJson.optString("executablePath", "")
                }
                if (originalJson.has("useLegacyDRM") && !originalJson.isNull("useLegacyDRM")) {
                    resultMap["useLegacyDRM"] = originalJson.optBoolean("useLegacyDRM", PrefManager.useLegacyDRM)
                }
                return resultMap
            }

            else {
                if (!originalJson.has("containerVariant") || originalJson.isNull("containerVariant")) {
                    Timber.tag("BestConfigService").w("containerVariant is missing or null in original config, returning empty map")
                    return mapOf()
                }

                val containerVariant = originalJson.optString("containerVariant", "")

                if (!originalJson.has("wineVersion") || originalJson.isNull("wineVersion")) {
                    if (containerVariant.equals(Container.GLIBC, ignoreCase = true)) {
                        originalJson.put("wineVersion", "wine-9.2-x86_64")
                    }
                    else {
                        Timber.tag("BestConfigService").w("wineVersion is missing or null in original config, returning empty map")
                        return mapOf()
                    }
                }
                if (!originalJson.has("dxwrapper") || originalJson.isNull("dxwrapper")) {
                    Timber.tag("BestConfigService").w("dxwrapper is missing or null in original config, returning empty map")
                    return mapOf()
                }
                if (!originalJson.has("dxwrapperConfig") || originalJson.isNull("dxwrapperConfig")) {
                    Timber.tag("BestConfigService").w("dxwrapperConfig is missing or null in original config, returning empty map")
                    return mapOf()
                }

                // Also check they're not empty strings
                val wineVersion = originalJson.optString("wineVersion", "")
                val dxwrapper = originalJson.optString("dxwrapper", "")
                val dxwrapperConfig = originalJson.optString("dxwrapperConfig", "")

                if (containerVariant.isEmpty()) {
                    Timber.tag("BestConfigService").w("containerVariant is empty in original config, returning empty map")
                    return mapOf()
                }
                if (wineVersion.isEmpty()) {
                    Timber.tag("BestConfigService").w("wineVersion is empty in original config, returning empty map")
                    return mapOf()
                }
                if (dxwrapper.isEmpty()) {
                    Timber.tag("BestConfigService").w("dxwrapper is empty in original config, returning empty map")
                    return mapOf()
                }
                if (dxwrapperConfig.isEmpty()) {
                    Timber.tag("BestConfigService").w("dxwrapperConfig is empty in original config, returning empty map")
                    return mapOf()
                }

                // Step 1: Filter config based on match type
                val updatedConfigJson = Json.parseToJsonElement(originalJson.toString()).jsonObject
                val filteredConfig = filterConfigByMatchType(updatedConfigJson, matchType)
                val filteredJson = JSONObject(filteredConfig.toString())

                // Step 2: Validate component versions against resource arrays
                if (!validateComponentVersions(context, filteredJson)) {
                    Timber.tag("BestConfigService").w("Component version validation failed, returning empty map")
                    return mapOf()
                }

                // Step 3: Build map with only fields present in filteredJson (not defaults)
                val resultMap = mutableMapOf<String, Any?>()
                if (filteredJson.has("executablePath") && !filteredJson.isNull("executablePath")) {
                    resultMap["executablePath"] = filteredJson.optString("executablePath", "")
                }
                if (filteredJson.has("graphicsDriver") && !filteredJson.isNull("graphicsDriver")) {
                    resultMap["graphicsDriver"] = filteredJson.optString("graphicsDriver", "")
                }
                if (filteredJson.has("graphicsDriverVersion") && !filteredJson.isNull("graphicsDriverVersion")) {
                    resultMap["graphicsDriverVersion"] = filteredJson.optString("graphicsDriverVersion", "")
                }
                if (filteredJson.has("graphicsDriverConfig") && !filteredJson.isNull("graphicsDriverConfig")) {
                    resultMap["graphicsDriverConfig"] = filteredJson.optString("graphicsDriverConfig", "")
                }
                if (filteredJson.has("dxwrapper") && !filteredJson.isNull("dxwrapper")) {
                    resultMap["dxwrapper"] = filteredJson.optString("dxwrapper", "")
                }
                if (filteredJson.has("dxwrapperConfig") && !filteredJson.isNull("dxwrapperConfig")) {
                    resultMap["dxwrapperConfig"] = filteredJson.optString("dxwrapperConfig", "")
                }
                if (filteredJson.has("execArgs") && !filteredJson.isNull("execArgs")) {
                    resultMap["execArgs"] = filteredJson.optString("execArgs", "")
                }
                if (filteredJson.has("startupSelection") && !filteredJson.isNull("startupSelection")) {
                    resultMap["startupSelection"] = filteredJson.optInt("startupSelection", PrefManager.startupSelection).toByte()
                }
                if (filteredJson.has("box64Version") && !filteredJson.isNull("box64Version")) {
                    resultMap["box64Version"] = filteredJson.optString("box64Version", "")
                }
                if (filteredJson.has("box64Preset") && !filteredJson.isNull("box64Preset")) {
                    resultMap["box64Preset"] = filteredJson.optString("box64Preset", "")
                }
                if (filteredJson.has("containerVariant") && !filteredJson.isNull("containerVariant")) {
                    resultMap["containerVariant"] = filteredJson.optString("containerVariant", "")
                }
                if (filteredJson.has("wineVersion") && !filteredJson.isNull("wineVersion")) {
                    resultMap["wineVersion"] = filteredJson.optString("wineVersion", "")
                }
                if (filteredJson.has("emulator") && !filteredJson.isNull("emulator")) {
                    resultMap["emulator"] = filteredJson.optString("emulator", "")
                }
                if (filteredJson.has("fexcoreVersion") && !filteredJson.isNull("fexcoreVersion")) {
                    resultMap["fexcoreVersion"] = filteredJson.optString("fexcoreVersion", "")
                }
                if (filteredJson.has("fexcoreTSOMode") && !filteredJson.isNull("fexcoreTSOMode")) {
                    resultMap["fexcoreTSOMode"] = filteredJson.optString("fexcoreTSOMode", "")
                }
                if (filteredJson.has("fexcoreX87Mode") && !filteredJson.isNull("fexcoreX87Mode")) {
                    resultMap["fexcoreX87Mode"] = filteredJson.optString("fexcoreX87Mode", "")
                }
                if (filteredJson.has("fexcoreMultiBlock") && !filteredJson.isNull("fexcoreMultiBlock")) {
                    resultMap["fexcoreMultiBlock"] = filteredJson.optString("fexcoreMultiBlock", "")
                }
                if (filteredJson.has("useLegacyDRM") && !filteredJson.isNull("useLegacyDRM")) {
                    resultMap["useLegacyDRM"] = filteredJson.optBoolean("useLegacyDRM", PrefManager.useLegacyDRM)
                }

                return resultMap
            }
        } catch (e: Exception) {
            Timber.tag("BestConfigService").e(e, "Failed to parse config to ContainerData: ${e.message}")
            return mapOf()
        }
    }
}

