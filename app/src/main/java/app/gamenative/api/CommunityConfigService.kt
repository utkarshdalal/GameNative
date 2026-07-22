package app.gamenative.api

import app.gamenative.BuildConfig
import app.gamenative.utils.Net
import com.winlator.container.Container
import java.io.IOException
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_CONCURRENT_DEVICE_REQUESTS = 4
private const val MAX_API_PAGE_SIZE = 200
private const val MAX_CONFIG_VALUE_CHARS = 64 * 1024
private const val MAX_METADATA_CHARS = 256
private const val MAX_NOTES_CHARS = 4 * 1024
private const val MAX_TAGS = 20
private const val MAX_TAG_CHARS = 64

enum class CommunityConfigSort(val apiValue: String) {
    HIGHEST_RATED("rating"),
    NEWEST("created_at"),
}

internal enum class CommunityGpuCompatibility {
    ADRENO_STANDARD,
    ADRENO_ELITE,
    OTHER,
    UNKNOWN,
}

data class CommunityGame(
    val id: Int,
    val name: String,
)

data class CommunityConfigDevice(
    val id: Int,
    val model: String,
    val gpu: String,
    val androidVersion: String,
    val soc: String,
)

data class CommunityConfigRun(
    val id: Long,
    val rating: Int,
    val averageFps: Double?,
    val tags: List<String>,
    val notes: String,
    val config: JsonObject,
    val createdAt: String,
    val appVersion: String,
    val sessionLengthSeconds: Long?,
    val gameStore: String,
    val device: CommunityConfigDevice,
) {
    fun configString(key: String): String {
        val element = config[key] ?: return ""
        return (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
    }
}

data class CommunityConfigPage(
    val runs: List<CommunityConfigRun>,
    val total: Int,
    val page: Int,
    val hasMore: Boolean,
)

class CommunityConfigApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

class CommunityConfigService(
    private val client: OkHttpClient = Net.http,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        private const val DEFAULT_BASE_URL = "https://api.gamenative.app"
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

        val shared: CommunityConfigService by lazy { CommunityConfigService() }
    }

    suspend fun searchGames(query: String): List<CommunityGame> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = endpoint("api/games/search")
            .addQueryParameter("q", query.trim())
            .build()
        parseGames(execute(url.toString()))
    }

    suspend fun findGame(query: String): CommunityGame? {
        return selectCommunityGame(query, searchGames(query))
    }

    suspend fun findDevices(
        manufacturer: String,
        model: String,
        gpu: String,
        androidVersion: String,
    ): List<CommunityConfigDevice> {
        val query = communityDeviceQuery(manufacturer, model)
        fun select(devices: List<CommunityConfigDevice>) = selectCommunityDevices(
            devices = devices,
            manufacturer = manufacturer,
            model = model,
            currentGpu = gpu,
            androidVersion = androidVersion,
        )
        val primaryMatches = select(searchDevices(query))
        if (primaryMatches.isNotEmpty() || query.equals(model.trim(), ignoreCase = true)) {
            return primaryMatches
        }
        return select(searchDevices(model))
    }

    suspend fun searchDevices(model: String): List<CommunityConfigDevice> = withContext(Dispatchers.IO) {
        if (model.isBlank()) return@withContext emptyList()
        val url = endpoint("api/devices")
            .addQueryParameter("model", model.trim())
            .build()
        parseDevices(execute(url.toString()))
    }

    suspend fun fetchConfigs(
        gameId: Int,
        gpu: String?,
        sort: CommunityConfigSort,
        page: Int,
        limit: Int = 20,
        deviceIds: List<Int> = emptyList(),
    ): CommunityConfigPage = withContext(Dispatchers.IO) {
        val validDeviceIds = deviceIds.filter { it > 0 }.distinct()
        val normalizedPage = page.coerceAtLeast(0)
        val normalizedLimit = limit.coerceIn(1, 50)
        if (validDeviceIds.size <= 1) {
            return@withContext fetchConfigPage(
                gameId = gameId,
                gpu = gpu,
                sort = sort,
                page = normalizedPage,
                limit = normalizedLimit,
                deviceId = validDeviceIds.singleOrNull(),
            )
        }

        val endExclusive = (normalizedPage + 1L) * normalizedLimit
        if (endExclusive > Int.MAX_VALUE) {
            throw CommunityConfigApiException("Compatibility page is too large")
        }
        val targetCount = endExclusive.toInt()
        val requestLimiter = Semaphore(minOf(validDeviceIds.size, MAX_CONCURRENT_DEVICE_REQUESTS))
        val slices = coroutineScope {
            validDeviceIds.map { deviceId ->
                async {
                    requestLimiter.withPermit {
                        fetchDeviceConfigSlice(
                            gameId = gameId,
                            sort = sort,
                            deviceId = deviceId,
                            targetCount = targetCount,
                            pageSize = normalizedLimit,
                        )
                    }
                }
            }.awaitAll()
        }
        val mergedRuns = sortCommunityRuns(
            slices.flatMap { it.runs }.distinctBy { it.id },
            sort,
        )
        val startIndex = (normalizedPage.toLong() * normalizedLimit).toInt()
        CommunityConfigPage(
            runs = mergedRuns.drop(startIndex).take(normalizedLimit),
            total = slices.sumOf { it.total.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            page = normalizedPage,
            hasMore = mergedRuns.size > endExclusive || slices.any { it.hasMore },
        )
    }

    suspend fun fetchCompatibleConfigs(
        gameId: Int,
        currentGpu: String,
        sort: CommunityConfigSort,
        page: Int,
    ): CommunityConfigPage = withContext(Dispatchers.IO) {
        val currentCompatibility = communityGpuCompatibility(currentGpu)
        if (currentCompatibility == CommunityGpuCompatibility.UNKNOWN) {
            return@withContext CommunityConfigPage(emptyList(), 0, page.coerceAtLeast(0), false)
        }

        val gpuQuery = "Adreno".takeIf {
            currentCompatibility == CommunityGpuCompatibility.ADRENO_STANDARD ||
                currentCompatibility == CommunityGpuCompatibility.ADRENO_ELITE
        }
        val result = fetchConfigPage(
            gameId = gameId,
            gpu = gpuQuery,
            sort = sort,
            page = page.coerceAtLeast(0),
            limit = MAX_API_PAGE_SIZE,
            deviceId = null,
        )
        val compatibleRuns = sortCommunityRuns(
            result.runs.filter { communityGpuCompatibility(it.device.gpu) == currentCompatibility },
            sort,
        )
        CommunityConfigPage(
            runs = compatibleRuns,
            total = compatibleRuns.size,
            page = result.page,
            hasMore = result.hasMore,
        )
    }

    private data class DeviceConfigSlice(
        val runs: List<CommunityConfigRun>,
        val total: Int,
        val hasMore: Boolean,
    )

    private fun fetchDeviceConfigSlice(
        gameId: Int,
        sort: CommunityConfigSort,
        deviceId: Int,
        targetCount: Int,
        pageSize: Int,
    ): DeviceConfigSlice {
        val runs = LinkedHashMap<Long, CommunityConfigRun>()
        var nextPage = 0
        var total = 0
        var hasMore: Boolean
        do {
            val result = fetchConfigPage(
                gameId = gameId,
                gpu = null,
                sort = sort,
                page = nextPage,
                limit = pageSize,
                deviceId = deviceId,
            )
            result.runs.forEach { runs.putIfAbsent(it.id, it) }
            total = maxOf(total, result.total)
            nextPage++
            val maximumPages = (total.toLong() + pageSize - 1) / pageSize
            hasMore = result.hasMore && nextPage < maximumPages
        } while (runs.size < targetCount && hasMore)

        return DeviceConfigSlice(
            runs = runs.values.toList(),
            total = total,
            hasMore = hasMore,
        )
    }

    private fun fetchConfigPage(
        gameId: Int,
        gpu: String?,
        sort: CommunityConfigSort,
        page: Int,
        limit: Int,
        deviceId: Int?,
    ): CommunityConfigPage {
        val urlBuilder = endpoint("api/compatibility")
            .addQueryParameter("gameId", gameId.toString())
            .addQueryParameter("sort", sort.apiValue)
            .addQueryParameter("dir", "desc")
            .addQueryParameter("page", page.coerceAtLeast(0).toString())
            .addQueryParameter("limit", limit.coerceIn(1, MAX_API_PAGE_SIZE).toString())
        if (deviceId != null && deviceId > 0) {
            urlBuilder.addQueryParameter("deviceId", deviceId.toString())
        } else {
            gpu?.trim()?.takeIf { it.isNotEmpty() }?.let {
                urlBuilder.addQueryParameter("gpu", it)
            }
        }
        return parseConfigPage(execute(urlBuilder.build().toString()))
    }

    private fun endpoint(path: String) = baseUrl
        .toHttpUrlOrNull()
        ?.newBuilder()
        ?.addPathSegments(path)
        ?: throw CommunityConfigApiException("Invalid compatibility API URL")

    private fun execute(url: String): String {
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val body = readBoundedBody(response.body)
                if (!response.isSuccessful) {
                    throw CommunityConfigApiException(
                        message = parseErrorMessage(body).ifBlank { "Compatibility service returned HTTP ${response.code}" },
                        statusCode = response.code,
                    )
                }
                return body
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: CommunityConfigApiException) {
            throw error
        } catch (error: IOException) {
            throw CommunityConfigApiException("Unable to reach the compatibility service", cause = error)
        }
    }

    private fun readBoundedBody(body: okhttp3.ResponseBody?): String {
        body ?: return ""
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw CommunityConfigApiException("Compatibility response is too large")
        }
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) {
            throw CommunityConfigApiException("Compatibility response is too large")
        }
        return source.readString(Charsets.UTF_8)
    }

    private fun parseGames(body: String): List<CommunityGame> {
        return try {
            val games = JSONObject(body).optJSONArray("games") ?: JSONArray()
            buildList {
                for (index in 0 until games.length()) {
                    val game = games.optJSONObject(index) ?: continue
                    val id = game.optInt("id", 0)
                    val name = game.cleanString("name")
                    if (id > 0 && name.isNotEmpty()) add(CommunityGame(id, name))
                }
            }
        } catch (error: Exception) {
            throw CommunityConfigApiException("Invalid game search response", cause = error)
        }
    }

    private fun parseDevices(body: String): List<CommunityConfigDevice> {
        return try {
            val devices = JSONObject(body).optJSONArray("devices") ?: JSONArray()
            buildList {
                for (index in 0 until devices.length()) {
                    parseDevice(devices.optJSONObject(index))
                        ?.takeIf { it.id > 0 }
                        ?.let(::add)
                }
            }
        } catch (error: Exception) {
            throw CommunityConfigApiException("Invalid device response", cause = error)
        }
    }

    private fun parseConfigPage(body: String): CommunityConfigPage {
        return try {
            val root = JSONObject(body)
            val runsJson = root.optJSONArray("runs") ?: JSONArray()
            val rawRunCount = runsJson.length()
            val runs = buildList {
                for (index in 0 until runsJson.length()) {
                    parseRun(runsJson.optJSONObject(index))?.let(::add)
                }
            }
            val page = root.optInt("page", 0).coerceAtLeast(0)
            val pageSize = root.optInt("pageSize", rawRunCount).coerceAtLeast(rawRunCount).coerceAtLeast(1)
            val total = root.optInt("total", rawRunCount).coerceAtLeast(rawRunCount)
            CommunityConfigPage(
                runs = runs,
                total = total,
                page = page,
                hasMore = rawRunCount > 0 && (page + 1L) * pageSize < total,
            )
        } catch (error: CommunityConfigApiException) {
            throw error
        } catch (error: Exception) {
            throw CommunityConfigApiException("Invalid compatibility response", cause = error)
        }
    }

    private fun parseRun(run: JSONObject?): CommunityConfigRun? {
        run ?: return null
        val id = run.optLong("id", 0L)
        if (id <= 0) return null
        val configObject = when (val value = run.opt("configs")) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return null
        val config = runCatching {
            Json.parseToJsonElement(configObject.toString()).jsonObject
        }.getOrNull() ?: return null
        val safeConfig = sanitizeCommunityConfig(config)
        if (!isValidCommunityConfig(safeConfig)) return null
        return CommunityConfigRun(
            id = id,
            rating = run.optInt("rating", 0).coerceIn(0, 5),
            averageFps = if (run.isNull("avgFps")) null else run.optDouble("avgFps").takeIf { it.isFinite() },
            tags = run.optJSONArray("tags").toStringList(),
            notes = run.cleanString("notes", MAX_NOTES_CHARS),
            config = safeConfig,
            createdAt = run.cleanString("createdAt"),
            appVersion = run.cleanString("appVersion"),
            sessionLengthSeconds = parseSessionLength(run, configObject),
            gameStore = parseGameStore(run, configObject),
            device = parseDevice(run.optJSONObject("device"), run.optInt("deviceId", 0))
                ?: CommunityConfigDevice(0, "", "", "", ""),
        )
    }

    private fun parseSessionLength(run: JSONObject, config: JSONObject): Long? {
        val sessionMetadata = config.optJSONObject("sessionMetadata")
        return sequenceOf(
            run.optPositiveLong("sessionLengthSec"),
            run.optPositiveLong("session_length_sec"),
            sessionMetadata?.optPositiveLong("sessionLengthSec"),
            sessionMetadata?.optPositiveLong("session_length_sec"),
        ).filterNotNull().firstOrNull()
    }

    private fun parseGameStore(run: JSONObject, config: JSONObject): String {
        val explicitStore = sequenceOf("gameStore", "game_store", "store")
            .map { run.cleanString(it) }
            .firstOrNull { it.isNotEmpty() }
        return normalizeCommunityGameStore(explicitStore.orEmpty()).ifEmpty {
            inferCommunityGameStore(config.cleanString("id"))
        }
    }

    private fun parseDevice(device: JSONObject?, fallbackId: Int = 0): CommunityConfigDevice? {
        device ?: return null
        val id = device.optInt("id", fallbackId)
        val model = device.cleanString("model")
        if (model.isEmpty()) return null
        return CommunityConfigDevice(
            id = id,
            model = model,
            gpu = device.cleanString("gpu"),
            androidVersion = device.cleanString("androidVer"),
            soc = device.cleanString("soc"),
        )
    }

    private fun parseErrorMessage(body: String): String {
        return runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message")
                is String -> error
                else -> root.optString("message")
            }
        }.getOrDefault("")
    }
}

internal fun selectCommunityGame(query: String, games: List<CommunityGame>): CommunityGame? {
    if (games.isEmpty()) return null
    val normalizedQuery = normalizeCommunityGameName(query)
    return games.firstOrNull { normalizeCommunityGameName(it.name) == normalizedQuery }
}

internal fun communityDeviceQuery(manufacturer: String, model: String): String {
    val cleanManufacturer = manufacturer.trim()
    val cleanModel = model.trim()
    if (cleanManufacturer.isEmpty()) return cleanModel
    if (cleanModel.startsWith(cleanManufacturer, ignoreCase = true)) return cleanModel
    return "$cleanManufacturer $cleanModel".trim()
}

internal fun selectCommunityDevices(
    devices: List<CommunityConfigDevice>,
    manufacturer: String,
    model: String,
    currentGpu: String,
    androidVersion: String,
): List<CommunityConfigDevice> {
    val canonicalGpu = canonicalCommunityGpu(currentGpu)
    val canonicalAndroid = canonicalCommunityAndroid(androidVersion)
    val fullModel = canonicalCommunityDeviceModel(communityDeviceQuery(manufacturer, model))
    val shortModel = canonicalCommunityDeviceModel(model)
    return devices
        .asSequence()
        .filter { device ->
            val candidate = canonicalCommunityDeviceModel(device.model)
            candidate == fullModel || candidate == shortModel
        }
        .filter { device ->
            val deviceGpu = canonicalCommunityGpu(device.gpu)
            canonicalGpu.isEmpty() || deviceGpu.isEmpty() || deviceGpu == canonicalGpu
        }
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<CommunityConfigDevice> {
                canonicalGpu.isNotEmpty() && canonicalCommunityGpu(it.gpu) == canonicalGpu
            }.thenByDescending {
                canonicalAndroid.isNotEmpty() && canonicalCommunityAndroid(it.androidVersion) == canonicalAndroid
            }.thenByDescending { it.id },
        )
        .toList()
}

internal fun communityConfigMatchType(currentGpu: String, configGpu: String): String {
    val current = canonicalCommunityGpu(currentGpu)
    val candidate = canonicalCommunityGpu(configGpu)
    val currentCompatibility = communityGpuCompatibility(currentGpu)
    val candidateCompatibility = communityGpuCompatibility(configGpu)
    return when {
        current.isNotEmpty() && current == candidate -> "exact_gpu_match"
        currentCompatibility == candidateCompatibility &&
            (currentCompatibility == CommunityGpuCompatibility.ADRENO_STANDARD ||
                currentCompatibility == CommunityGpuCompatibility.ADRENO_ELITE) -> "gpu_family_match"
        else -> "fallback_match"
    }
}

internal fun communityGpuCompatibility(value: String): CommunityGpuCompatibility {
    return when (val gpu = canonicalCommunityGpu(value)) {
        "" -> CommunityGpuCompatibility.UNKNOWN
        else -> when {
            gpu.matches(Regex("adreno:[67][0-9]{2}")) -> CommunityGpuCompatibility.ADRENO_STANDARD
            gpu == "adreno:a12" || gpu.matches(Regex("adreno:8[3-5][0-9]")) -> {
                CommunityGpuCompatibility.ADRENO_ELITE
            }
            else -> CommunityGpuCompatibility.OTHER
        }
    }
}

internal fun normalizeCommunityGameStore(value: String): String {
    val normalized = value.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]+"), "")
    return when (normalized) {
        "steam" -> "steam"
        "epic", "epicgames", "epicgamesstore" -> "epic"
        "gog", "gogcom" -> "gog"
        "amazon", "amazongames" -> "amazon"
        "custom", "customgame" -> "custom"
        else -> value.trim().take(MAX_METADATA_CHARS)
    }
}

private fun inferCommunityGameStore(configId: String): String {
    val normalized = configId.uppercase(Locale.ENGLISH)
    return when {
        normalized.startsWith("STEAM_") -> "steam"
        normalized.startsWith("EPIC_") -> "epic"
        normalized.startsWith("GOG_") -> "gog"
        normalized.startsWith("AMAZON_") -> "amazon"
        normalized.startsWith("CUSTOM_GAME_") -> "custom"
        else -> ""
    }
}

internal fun canonicalCommunityGpu(value: String): String {
    val cleaned = value
        .lowercase(Locale.ENGLISH)
        .replace("(tm)", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isEmpty()) return ""
    if (cleaned.contains("unknown") || cleaned == "n/a") return ""

    Regex("\\badreno[ -]*([a-z]?\\d+)\\b").find(cleaned)?.let {
        return "adreno:${it.groupValues[1]}"
    }
    Regex("\\b(mali|immortalis)[ -]*([a-z]\\d+)\\b").find(cleaned)?.let {
        return "arm:${it.groupValues[2]}"
    }
    Regex("\\bxclipse[ -]*(\\d+)\\b").find(cleaned)?.let {
        return "xclipse:${it.groupValues[1]}"
    }
    return cleaned.replace(Regex("[^a-z0-9]+"), "")
}

private fun normalizeCommunityGameName(value: String): String = value
    .lowercase(Locale.ENGLISH)
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

private fun canonicalCommunityAndroid(value: String): String = value
    .lowercase(Locale.ENGLISH)
    .replace("android", "")
    .trim()

private fun canonicalCommunityDeviceModel(value: String): String = value
    .lowercase(Locale.ENGLISH)
    .replace(Regex("[^a-z0-9]+"), "")

private val communityConfigAllowedKeys = setOf(
    "graphicsDriver",
    "graphicsDriverVersion",
    "graphicsDriverConfig",
    "dxwrapper",
    "dxwrapperConfig",
    "startupSelection",
    "box64Version",
    "box64Preset",
    "containerVariant",
    "wineVersion",
    "emulator",
    "fexcoreVersion",
    "fexcoreTSOMode",
    "fexcoreX87Mode",
    "fexcoreMultiBlock",
    "fexcorePreset",
    "useLegacyDRM",
    "audioDriver",
    "wincomponents",
    "videoMemorySize",
)

internal fun sanitizeCommunityConfig(config: JsonObject): JsonObject = JsonObject(
    config.filter { (key, value) ->
        key in communityConfigAllowedKeys &&
            value is JsonPrimitive &&
            value.contentOrNull?.length?.let { it <= MAX_CONFIG_VALUE_CHARS } == true
    },
)

internal fun isValidCommunityConfig(
    config: JsonObject,
    allowGlibc: Boolean = !BuildConfig.MODERN_ANDROID,
): Boolean {
    fun value(key: String) = (config[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    val variant = value("containerVariant")
    if (!variant.equals(Container.BIONIC, ignoreCase = true) &&
        !variant.equals(Container.GLIBC, ignoreCase = true)
    ) {
        return false
    }
    if (!allowGlibc && variant.equals(Container.GLIBC, ignoreCase = true)) return false
    if (!variant.equals(Container.GLIBC, ignoreCase = true) && value("wineVersion").isEmpty()) return false
    return value("dxwrapper").isNotEmpty() && value("dxwrapperConfig").isNotEmpty()
}

internal fun sortCommunityRuns(
    runs: List<CommunityConfigRun>,
    sort: CommunityConfigSort,
): List<CommunityConfigRun> {
    fun CommunityConfigRun.createdAtMillis(): Long = runCatching {
        OffsetDateTime.parse(createdAt).toInstant().toEpochMilli()
    }.getOrDefault(Long.MIN_VALUE)

    val comparator = when (sort) {
        CommunityConfigSort.HIGHEST_RATED -> compareByDescending<CommunityConfigRun> { it.rating }
            .thenByDescending { it.createdAtMillis() }
            .thenByDescending { it.id }
        CommunityConfigSort.NEWEST -> compareByDescending<CommunityConfigRun> { it.createdAtMillis() }
            .thenByDescending { it.id }
    }
    return runs.sortedWith(comparator)
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until minOf(length(), MAX_TAGS)) {
            if (isNull(index)) continue
            optString(index).trim().take(MAX_TAG_CHARS).takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}

private fun JSONObject.cleanString(key: String, maxLength: Int = MAX_METADATA_CHARS): String {
    if (isNull(key)) return ""
    return optString(key).trim().take(maxLength)
}

private fun JSONObject.optPositiveLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }?.takeIf { it > 0 }
}
