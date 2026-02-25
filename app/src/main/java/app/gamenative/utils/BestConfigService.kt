package app.gamenative.utils

import android.content.Context
import androidx.compose.ui.graphics.Color
import app.gamenative.PrefManager
import app.gamenative.R
import com.winlator.box86_64.Box86_64PresetManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.contents.ContentProfile
import com.winlator.fexcore.FEXCorePresetManager
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
import java.util.Locale
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

    // Last missing content description from validation (e.g. "DXVK 1.10.3")
    private var lastMissingContentDescription: String? = null

    fun consumeLastMissingContentDescription(): String? {
        val result = lastMissingContentDescription
        lastMissingContentDescription = null
        return result
    }
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

    data class ManifestInstallRequest(
        val entry: ManifestEntry,
        val contentType: ContentProfile.ContentType? = null,
        val isDriver: Boolean = false,
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
     * Returns a human-readable description of the first missing component (e.g. "DXVK 1.10.3"),
     * or null if all referenced versions exist.
     */
    private suspend fun validateComponentVersions(context: Context, filteredJson: JSONObject): String? {
        // Get resource arrays (same as ContainerConfigDialog)
        val dxvkVersions = context.resources.getStringArray(R.array.dxvk_version_entries).toList()
        val vkd3dVersions = context.resources.getStringArray(R.array.vkd3d_version_entries).toList()
        val box64Versions = context.resources.getStringArray(R.array.box64_version_entries).toList()
        val box64BionicVersions = context.resources.getStringArray(R.array.box64_bionic_version_entries).toList()
        val wowBox64Versions = context.resources.getStringArray(R.array.wowbox64_version_entries).toList()
        val fexcoreVersions = context.resources.getStringArray(R.array.fexcore_version_entries).toList()
        val bionicWineEntries = context.resources.getStringArray(R.array.bionic_wine_entries).toList()
        val glibcWineEntries = context.resources.getStringArray(R.array.glibc_wine_entries).toList()
        val installed = ManifestComponentHelper.loadInstalledContentLists(context)
        val manifest = ManifestRepository.loadManifest(context)

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
        val manifestDxvk = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.DXVK].orEmpty(),
            containerVariant,
        )
        val manifestVkd3d = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.VKD3D].orEmpty(),
            containerVariant,
        )
        val manifestBox64 = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.BOX64].orEmpty(),
            containerVariant,
        )
        val manifestWowBox64 = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.WOWBOX64].orEmpty(),
            containerVariant,
        )
        val manifestFexcore = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.FEXCORE].orEmpty(),
            containerVariant,
        )
        val manifestDrivers = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.DRIVER].orEmpty(),
            containerVariant,
        )
        val manifestWine = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.WINE].orEmpty(),
            containerVariant,
        )
        val manifestProton = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.PROTON].orEmpty(),
            containerVariant,
        )

        // Build available versions upfront (base + installed + manifest)
        val installedContent = installed.installed
        val availableDxvk = ManifestComponentHelper.buildAvailableVersions(
            dxvkVersions,
            installedContent.dxvk,
            manifestDxvk,
        )
        val availableVkd3d = ManifestComponentHelper.buildAvailableVersions(
            vkd3dVersions,
            installedContent.vkd3d,
            manifestVkd3d,
        )
        val availableBox64Bionic = ManifestComponentHelper.buildAvailableVersions(
            box64BionicVersions,
            installedContent.box64,
            manifestBox64,
        )
        val availableBox64Glibc = ManifestComponentHelper.buildAvailableVersions(
            box64Versions,
            installedContent.box64,
            manifestBox64,
        )
        val availableWowBox64 = ManifestComponentHelper.buildAvailableVersions(
            wowBox64Versions,
            installedContent.wowBox64,
            manifestWowBox64,
        )
        val availableFexcore = ManifestComponentHelper.buildAvailableVersions(
            fexcoreVersions,
            installedContent.fexcore,
            manifestFexcore,
        )
        val availableWineBionic = ManifestComponentHelper.buildAvailableVersions(
            bionicWineEntries,
            installedContent.wine + installedContent.proton,
            manifestWine + manifestProton,
        )
        val availableWineGlibc = ManifestComponentHelper.buildAvailableVersions(
            glibcWineEntries,
            installedContent.wine + installedContent.proton,
            manifestWine + manifestProton,
        )
        val availableDrivers = ManifestComponentHelper.buildAvailableVersions(
            context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries).toList(),
            installed.installedDrivers,
            manifestDrivers,
        )

        // Validate DXVK version
        if (dxwrapper == "dxvk" && dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(dxwrapperConfig)
            val version = kvs.get("version")
            if (version.isNotEmpty() && !ManifestComponentHelper.versionExists(version, availableDxvk)) {
                Timber.tag("BestConfigService").w("DXVK version $version not found, updating to PrefManager default")
                return "DXVK $version"
                filteredJson.put("dxwrapperConfig", PrefManager.dxWrapperConfig)
            }
        }

        // Validate VKD3D version
        if (dxwrapper == "vkd3d" && dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(dxwrapperConfig)
            val version = kvs.get("vkd3dVersion")
            if (version.isNotEmpty() && !ManifestComponentHelper.versionExists(version, availableVkd3d)) {
                Timber.tag("BestConfigService").w("VKD3D version $version not found, updating to PrefManager default")
                return "VKD3D $version"
                filteredJson.put("dxwrapperConfig", PrefManager.dxWrapperConfig)
            }
        }

        // Validate Box64 version (check separately based on container variant)
        if (box64Version.isNotEmpty() && containerVariant.isNotEmpty()) {
            val box64VersionsToCheck = when {
                containerVariant.equals(Container.BIONIC, ignoreCase = true) -> availableBox64Bionic
                containerVariant.equals(Container.GLIBC, ignoreCase = true) -> availableBox64Glibc
                else -> {
                    Timber.tag("BestConfigService").w("Unknown container variant '$containerVariant', defaulting to glibc Box64 versions")
                    availableBox64Glibc
                }
            }
            if (!ManifestComponentHelper.versionExists(box64Version, box64VersionsToCheck)) {
                Timber.tag("BestConfigService").w("Box64 version $box64Version not found in $containerVariant variant entries, updating to PrefManager default")
                return "Box64 $box64Version"
                filteredJson.put("box64Version", PrefManager.box64Version)
            }
        }

        // Validate WoWBox64 version (if wineVersion contains arm64ec)
        if (wineVersion.contains("arm64ec", ignoreCase = true)) {
            if (box64Version.isNotEmpty() && !ManifestComponentHelper.versionExists(box64Version, availableWowBox64) && emulator != "FEXCore") {
                Timber.tag("BestConfigService").w("WoWBox64 version $box64Version not found, updating to PrefManager default")
                return "WoWBox64 $box64Version"
            }
        }

        // Validate FEXCore version
        if (fexcoreVersion.isNotEmpty() && !ManifestComponentHelper.versionExists(fexcoreVersion, availableFexcore)) {
            Timber.tag("BestConfigService").w("FEXCore version $fexcoreVersion not found, updating to PrefManager default")
            return "FEXCore $fexcoreVersion"
            filteredJson.put("fexcoreVersion", PrefManager.fexcoreVersion)
        }

        // Validate Wine/Proton version (check separately based on container variant)
        if (wineVersion.isNotEmpty() && containerVariant.isNotEmpty()) {
            val wineVersionsToCheck = when {
                containerVariant.equals(Container.BIONIC, ignoreCase = true) -> availableWineBionic
                containerVariant.equals(Container.GLIBC, ignoreCase = true) -> availableWineGlibc
                else -> {
                    Timber.tag("BestConfigService").w("Unknown container variant '$containerVariant', checking against all wine versions")
                    (availableWineBionic + availableWineGlibc).distinct()
                }
            }
            if (!ManifestComponentHelper.versionExists(wineVersion, wineVersionsToCheck)) {
                Timber.tag("BestConfigService").w("Wine version $wineVersion not found in $containerVariant variant entries, updating to PrefManager default")
                return "Wine $wineVersion"
                filteredJson.put("wineVersion", PrefManager.wineVersion)
            }
        }

        // Validate graphics driver version (from graphicsDriverConfig)
        if (containerVariant.equals(Container.BIONIC, ignoreCase = true) && graphicsDriverConfig.isNotEmpty()) {
            val firstSplit = graphicsDriverConfig.split(";")
            val parts = if (firstSplit.size > 1) firstSplit else graphicsDriverConfig.split(",")
            val configMap = parts.associate { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) kv[0] to kv[1] else part to ""
            }
            val driverVersion = configMap["version"] ?: ""
            if (driverVersion.isNotEmpty() && !ManifestComponentHelper.versionExists(driverVersion, availableDrivers)) {
                Timber.tag("BestConfigService")
                    .w("Graphics driver version $driverVersion not found for $containerVariant variant, updating to PrefManager default")
                return "Graphics driver $driverVersion"
            }
        }

        // Validate Box64 preset
        if (box64Preset.isNotEmpty()) {
            val preset = Box86_64PresetManager.getPreset("box64", context, box64Preset)
            if (preset == null) {
                Timber.tag("BestConfigService").w("Box64 preset $box64Preset not found, updating to PrefManager default")
                return "Box64 preset $box64Preset"
                filteredJson.put("box64Preset", PrefManager.box64Preset)
            }
        }

        // Validate FEXCore preset
        val fexcorePreset = filteredJson.optString("fexcorePreset", "")
        if (fexcorePreset.isNotEmpty()) {
            val preset = FEXCorePresetManager.getPreset(context, fexcorePreset)
            if (preset == null) {
                Timber.tag("BestConfigService").w("FEXCore preset $fexcorePreset not found, updating to PrefManager default")
                return "FEXCore preset $fexcorePreset"
                filteredJson.put("fexcorePreset", PrefManager.fexcorePreset)
            }
        }

        return null
    }

    suspend fun resolveMissingManifestInstallRequests(
        context: Context,
        configJson: JsonObject,
        matchType: String,
    ): List<ManifestInstallRequest> {
        val updatedConfigJson = Json.parseToJsonElement(configJson.toString()).jsonObject
        val filteredConfig = filterConfigByMatchType(updatedConfigJson, matchType)
        val filteredJson = JSONObject(filteredConfig.toString())
        val installed = ManifestComponentHelper.loadInstalledContentLists(context)
        val manifest = ManifestRepository.loadManifest(context)
        val installedContent = installed.installed

        val containerVariant = filteredJson.optString("containerVariant", "")
        val dxwrapper = filteredJson.optString("dxwrapper", "")
        val dxwrapperConfig = filteredJson.optString("dxwrapperConfig", "")
        val box64Version = filteredJson.optString("box64Version", "")
        val wineVersion = filteredJson.optString("wineVersion", "")
        val emulator = filteredJson.optString("emulator", "")
        val fexcoreVersion = filteredJson.optString("fexcoreVersion", "")
        val graphicsDriverConfig = filteredJson.optString("graphicsDriverConfig", "")

        val manifestDxvk = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.DXVK].orEmpty(),
            containerVariant,
        )
        val manifestVkd3d = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.VKD3D].orEmpty(),
            containerVariant,
        )
        val manifestBox64 = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.BOX64].orEmpty(),
            containerVariant,
        )
        val manifestWowBox64 = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.WOWBOX64].orEmpty(),
            containerVariant,
        )
        val manifestFexcore = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.FEXCORE].orEmpty(),
            containerVariant,
        )
        val manifestDrivers = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.DRIVER].orEmpty(),
            containerVariant,
        )
        val manifestWine = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.WINE].orEmpty(),
            containerVariant,
        )
        val manifestProton = ManifestComponentHelper.filterManifestByVariant(
            manifest.items[ManifestContentTypes.PROTON].orEmpty(),
            containerVariant,
        )

        // Build locally available versions upfront (base + installed, NO manifest)
        val baseDxvk = context.resources.getStringArray(R.array.dxvk_version_entries).toList()
        val baseVkd3d = context.resources.getStringArray(R.array.vkd3d_version_entries).toList()
        val baseBox64Bionic = context.resources.getStringArray(R.array.box64_bionic_version_entries).toList()
        val baseBox64Glibc = context.resources.getStringArray(R.array.box64_version_entries).toList()
        val baseWowBox64 = context.resources.getStringArray(R.array.wowbox64_version_entries).toList()
        val baseFexcore = context.resources.getStringArray(R.array.fexcore_version_entries).toList()
        val baseWineBionic = context.resources.getStringArray(R.array.bionic_wine_entries).toList()
        val baseWineGlibc = context.resources.getStringArray(R.array.glibc_wine_entries).toList()
        val baseDrivers = context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries).toList()

        val locallyAvailableDxvk = ManifestComponentHelper.buildAvailableVersions(
            base = baseDxvk,
            installed = installedContent.dxvk,
            manifest = emptyList(),
        )
        val locallyAvailableVkd3d = ManifestComponentHelper.buildAvailableVersions(
            base = baseVkd3d,
            installed = installedContent.vkd3d,
            manifest = emptyList(),
        )
        val locallyAvailableBox64Bionic = ManifestComponentHelper.buildAvailableVersions(
            base = baseBox64Bionic,
            installed = installedContent.box64,
            manifest = emptyList(),
        )
        val locallyAvailableBox64Glibc = ManifestComponentHelper.buildAvailableVersions(
            base = baseBox64Glibc,
            installed = installedContent.box64,
            manifest = emptyList(),
        )
        val locallyAvailableWowBox64 = ManifestComponentHelper.buildAvailableVersions(
            base = baseWowBox64,
            installed = installedContent.wowBox64,
            manifest = emptyList(),
        )
        val locallyAvailableFexcore = ManifestComponentHelper.buildAvailableVersions(
            base = baseFexcore,
            installed = installedContent.fexcore,
            manifest = emptyList(),
        )
        val locallyAvailableWineBionic = ManifestComponentHelper.buildAvailableVersions(
            base = baseWineBionic,
            installed = installedContent.wine + installedContent.proton,
            manifest = emptyList(),
        )
        val locallyAvailableWineGlibc = ManifestComponentHelper.buildAvailableVersions(
            base = baseWineGlibc,
            installed = installedContent.wine + installedContent.proton,
            manifest = emptyList(),
        )
        val locallyAvailableDrivers = ManifestComponentHelper.buildAvailableVersions(
            base = baseDrivers,
            installed = installed.installedDrivers,
            manifest = emptyList(),
        )

        val requests = LinkedHashMap<String, ManifestInstallRequest>()
        fun addRequest(entry: ManifestEntry, contentType: ContentProfile.ContentType? = null, isDriver: Boolean = false) {
            val key = entry.id.lowercase(Locale.ENGLISH)
            if (!requests.containsKey(key)) {
                requests[key] = ManifestInstallRequest(entry = entry, contentType = contentType, isDriver = isDriver)
            }
        }

        if (dxwrapper == "dxvk" && dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(dxwrapperConfig)
            val version = kvs.get("version")
            if (version.isNotEmpty() && !ManifestComponentHelper.versionExists(version, locallyAvailableDxvk)) {
                // Not locally available: if it exists in the manifest, enqueue it for download
                val entry = ManifestComponentHelper.findManifestEntryForVersion(version, manifestDxvk)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_DXVK)
                }
            }
        }

        if (dxwrapper == "vkd3d" && dxwrapperConfig.isNotEmpty()) {
            val kvs = KeyValueSet(dxwrapperConfig)
            val version = kvs.get("vkd3dVersion")
            if (version.isNotEmpty() && !ManifestComponentHelper.versionExists(version, locallyAvailableVkd3d)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(version, manifestVkd3d)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_VKD3D)
                }
            }
        }

        if (box64Version.isNotEmpty() && containerVariant.isNotEmpty()) {
            val locallyAvailableBox64 = when {
                containerVariant.equals(Container.BIONIC, ignoreCase = true) -> locallyAvailableBox64Bionic
                containerVariant.equals(Container.GLIBC, ignoreCase = true) -> locallyAvailableBox64Glibc
                else -> locallyAvailableBox64Glibc
            }
            if (!ManifestComponentHelper.versionExists(box64Version, locallyAvailableBox64)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(box64Version, manifestBox64)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_BOX64)
                }
            }
        }

        if (wineVersion.contains("arm64ec", ignoreCase = true) && emulator != "FEXCore") {
            if (box64Version.isNotEmpty() && !ManifestComponentHelper.versionExists(box64Version, locallyAvailableWowBox64)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(box64Version, manifestWowBox64)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)
                }
            }
        }

        if (fexcoreVersion.isNotEmpty()) {
            if (!ManifestComponentHelper.versionExists(fexcoreVersion, locallyAvailableFexcore)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(fexcoreVersion, manifestFexcore)
                if (entry != null) {
                    addRequest(entry, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)
                }
            }
        }

        if (wineVersion.isNotEmpty() && containerVariant.isNotEmpty()) {
            val locallyAvailableWine = when {
                containerVariant.equals(Container.BIONIC, ignoreCase = true) -> locallyAvailableWineBionic
                containerVariant.equals(Container.GLIBC, ignoreCase = true) -> locallyAvailableWineGlibc
                else -> (locallyAvailableWineBionic + locallyAvailableWineGlibc).distinct()
            }
            if (!ManifestComponentHelper.versionExists(wineVersion, locallyAvailableWine)) {
                val wineEntry = ManifestComponentHelper.findManifestEntryForVersion(wineVersion, manifestWine)
                val protonEntry = if (wineEntry == null) {
                    ManifestComponentHelper.findManifestEntryForVersion(wineVersion, manifestProton)
                } else null
                when {
                    wineEntry != null -> addRequest(wineEntry, ContentProfile.ContentType.CONTENT_TYPE_WINE)
                    protonEntry != null -> addRequest(protonEntry, ContentProfile.ContentType.CONTENT_TYPE_PROTON)
                }
            }
        }

        if (containerVariant.equals(Container.BIONIC, ignoreCase = true) && graphicsDriverConfig.isNotEmpty()) {
            val firstSplit = graphicsDriverConfig.split(";")
            val parts = if (firstSplit.size > 1) firstSplit else graphicsDriverConfig.split(",")
            val configMap = parts.associate { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) kv[0] to kv[1] else part to ""
            }
            val driverVersion = configMap["version"] ?: ""
            if (driverVersion.isNotEmpty() && !ManifestComponentHelper.versionExists(driverVersion, locallyAvailableDrivers)) {
                val entry = ManifestComponentHelper.findManifestEntryForVersion(driverVersion, manifestDrivers)
                if (entry != null) {
                    addRequest(entry, isDriver = true)
                }
            }
        }

        return requests.values.toList()
    }

    /**
     * Parses bestConfig JSON into a map of fields to update.
     * First parses values (using PrefManager defaults for validation), then validates component versions.
     * Returns map with only fields present in config (no defaults), or empty map if validation fails.
     */
    suspend fun parseConfigToContainerData(context: Context, configJson: JsonObject, matchType: String, applyKnownConfig: Boolean): Map<String, Any?>? {
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
                val missingContent = validateComponentVersions(context, filteredJson)
                if (missingContent != null) {
                    lastMissingContentDescription = missingContent
                    Timber.tag("BestConfigService").w("Component version validation failed for: $missingContent, returning empty map")
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
                if (filteredJson.has("fexcorePreset") && !filteredJson.isNull("fexcorePreset")) {
                    resultMap["fexcorePreset"] = filteredJson.optString("fexcorePreset", "")
                }
                if (filteredJson.has("useLegacyDRM") && !filteredJson.isNull("useLegacyDRM")) {
                    resultMap["useLegacyDRM"] = filteredJson.optBoolean("useLegacyDRM", PrefManager.useLegacyDRM)
                }
                if (filteredJson.has("steamOfflineMode") && !filteredJson.isNull("steamOfflineMode")) {
                    resultMap["steamOfflineMode"] = filteredJson.optBoolean("steamOfflineMode", PrefManager.steamOfflineMode)
                }
                if (filteredJson.has("envVars") && !filteredJson.isNull("envVars")) {
                    resultMap["envVars"] = filteredJson.optString("envVars", PrefManager.envVars)
                }
                if (filteredJson.has("cpuList") && !filteredJson.isNull("cpuList")) {
                    resultMap["cpuList"] = filteredJson.optString("cpuList", PrefManager.cpuList)
                }
                if (filteredJson.has("cpuListWoW64") && !filteredJson.isNull("cpuListWoW64")) {
                    resultMap["cpuListWoW64"] = filteredJson.optString("cpuListWoW64", PrefManager.cpuListWoW64)
                }
                if (filteredJson.has("audioDriver") && !filteredJson.isNull("audioDriver")) {
                    resultMap["audioDriver"] = filteredJson.optString("audioDriver", PrefManager.audioDriver)
                }
                if (filteredJson.has("wincomponents") && !filteredJson.isNull("wincomponents")) {
                    resultMap["wincomponents"] = filteredJson.optString("wincomponents", PrefManager.winComponents)
                }
                if (filteredJson.has("videoMemorySize") && !filteredJson.isNull("videoMemorySize")) {
                    resultMap["videoMemorySize"] = filteredJson.optString("videoMemorySize", PrefManager.videoMemorySize)
                }

                return resultMap
            }
        } catch (e: Exception) {
            Timber.tag("BestConfigService").e(e, "Failed to parse config to ContainerData: ${e.message}")
            return mapOf()
        }
    }
}

