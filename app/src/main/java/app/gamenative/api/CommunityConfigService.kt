package app.gamenative.api

import app.gamenative.utils.Net
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

enum class CommunityConfigSort(val apiValue: String) {
    HIGHEST_RATED("rating"),
    NEWEST("created_at"),
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
    val gameName: String,
    val device: CommunityConfigDevice,
) {
    val configStore: String?
        get() = configString("id")
            .substringBefore('_', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }

    fun configString(key: String): String {
        val element = config[key] ?: return ""
        return (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
    }
}

data class CommunityConfigPage(
    val runs: List<CommunityConfigRun>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
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
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024

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

    suspend fun fetchConfigs(
        gameId: Int,
        gpu: String?,
        sort: CommunityConfigSort,
        page: Int,
        limit: Int = 20,
    ): CommunityConfigPage = withContext(Dispatchers.IO) {
        val urlBuilder = endpoint("api/compatibility")
            .addQueryParameter("gameId", gameId.toString())
            .addQueryParameter("sort", sort.apiValue)
            .addQueryParameter("dir", "desc")
            .addQueryParameter("page", page.coerceAtLeast(0).toString())
            .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
        gpu?.trim()?.takeIf { it.isNotEmpty() }?.let {
            urlBuilder.addQueryParameter("gpu", it)
        }
        parseConfigPage(execute(urlBuilder.build().toString()))
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
                val body = response.body?.string().orEmpty()
                if (body.length > MAX_RESPONSE_CHARS) {
                    throw CommunityConfigApiException("Compatibility response is too large")
                }
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

    private fun parseGames(body: String): List<CommunityGame> {
        return try {
            val games = JSONObject(body).optJSONArray("games") ?: JSONArray()
            buildList {
                for (index in 0 until games.length()) {
                    val game = games.optJSONObject(index) ?: continue
                    val id = game.optInt("id", 0)
                    val name = game.optString("name").trim()
                    if (id > 0 && name.isNotEmpty()) add(CommunityGame(id, name))
                }
            }
        } catch (error: Exception) {
            throw CommunityConfigApiException("Invalid game search response", cause = error)
        }
    }

    private fun parseConfigPage(body: String): CommunityConfigPage {
        return try {
            val root = JSONObject(body)
            val runsJson = root.optJSONArray("runs") ?: JSONArray()
            val runs = buildList {
                for (index in 0 until runsJson.length()) {
                    parseRun(runsJson.optJSONObject(index))?.let(::add)
                }
            }
            CommunityConfigPage(
                runs = runs,
                total = root.optInt("total", runs.size).coerceAtLeast(runs.size),
                page = root.optInt("page", 0).coerceAtLeast(0),
                pageSize = root.optInt("pageSize", runs.size).coerceAtLeast(runs.size),
            )
        } catch (error: CommunityConfigApiException) {
            throw error
        } catch (error: Exception) {
            throw CommunityConfigApiException("Invalid compatibility response", cause = error)
        }
    }

    private fun parseRun(run: JSONObject?): CommunityConfigRun? {
        run ?: return null
        val configObject = when (val value = run.opt("configs")) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return null
        val config = runCatching {
            Json.parseToJsonElement(configObject.toString()).jsonObject
        }.getOrNull() ?: return null
        val deviceJson = run.optJSONObject("device") ?: JSONObject()
        val gameName = run.optString("gameName").ifBlank {
            run.optJSONObject("game")?.optString("name").orEmpty()
        }
        return CommunityConfigRun(
            id = run.optLong("id", 0L),
            rating = run.optInt("rating", 0).coerceIn(0, 5),
            averageFps = if (run.isNull("avgFps")) null else run.optDouble("avgFps").takeIf { it.isFinite() },
            tags = run.optJSONArray("tags").toStringList(),
            notes = run.optString("notes").trim(),
            config = config,
            createdAt = run.optString("createdAt"),
            appVersion = run.optString("appVersion"),
            gameName = gameName,
            device = CommunityConfigDevice(
                id = deviceJson.optInt("id", run.optInt("deviceId", 0)),
                model = deviceJson.optString("model"),
                gpu = deviceJson.optString("gpu"),
                androidVersion = deviceJson.optString("androidVer"),
                soc = deviceJson.optString("soc"),
            ),
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
        ?: games.first()
}

internal fun communityConfigMatchType(currentGpu: String, configGpu: String): String {
    val current = canonicalCommunityGpu(currentGpu)
    val candidate = canonicalCommunityGpu(configGpu)
    return if (current.isNotEmpty() && current == candidate) {
        "exact_gpu_match"
    } else {
        "fallback_match"
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

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}
