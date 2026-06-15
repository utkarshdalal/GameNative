package app.gamenative.utils

import app.gamenative.api.ApiResult
import app.gamenative.api.GameNativeApi
import app.gamenative.data.GameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import timber.log.Timber

/**
 * Fetches game compatibility stats from the GameNative API.
 *
 * Two endpoints share the same response shape - one keyed by device + GPU, one keyed by GPU only:
 *
 *   { "games": { "STEAM": { "Balatro": [n, mfps, s5, secs], ... }, "EPIC": {…}, ... } }
 *
 * where n = successful runs, mfps = median fps, s5 = 5-star reviews, secs = median session length.
 * The server filters modern/legacy results based on the modernBuild query param and returns them
 * under "games" (we also honor a "games_modern" key if a future response provides one).
 */
object DeviceGameStatsService {

    // Hardcoded to production to match GameCompatibilityService (the "Compatible" badge source).
    // GameNativeApi.BASE_URL points at http://10.0.2.2:8787 in debug builds, which is unreachable
    // from a physical device.
    private const val DEVICE_URL = "https://api.gamenative.app/api/device-game-stats"
    private const val GPU_URL = "https://api.gamenative.app/api/gpu-game-stats"

    data class DeviceGameStats(
        val successfulRuns: Int,
        val medianFps: Int,
        val fiveStarReviews: Int,
        val medianSessionSec: Int,
    )

    /** Stats for the current device + GPU. */
    suspend fun fetchForDevice(
        deviceModel: String,
        gpuName: String,
        modernBuild: Boolean,
    ): Map<GameSource, Map<String, DeviceGameStats>>? {
        val url = DEVICE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("deviceModel", deviceModel)
            .addQueryParameter("gpuName", gpuName)
            .addQueryParameter("modernBuild", modernBuild.toString())
            .build()
            .toString()
        return fetch(url, modernBuild, "device")
    }

    /** Stats for the current GPU across all devices. */
    suspend fun fetchForGpu(
        gpuName: String,
        modernBuild: Boolean,
    ): Map<GameSource, Map<String, DeviceGameStats>>? {
        val url = GPU_URL.toHttpUrl().newBuilder()
            .addQueryParameter("gpuName", gpuName)
            .addQueryParameter("modernBuild", modernBuild.toString())
            .build()
            .toString()
        return fetch(url, modernBuild, "gpu")
    }

    private suspend fun fetch(
        url: String,
        modernBuild: Boolean,
        tag: String,
    ): Map<GameSource, Map<String, DeviceGameStats>>? = withContext(Dispatchers.IO) {
        Timber.tag("DeviceGameStatsService").d("Fetching $tag game stats: $url")

        val request = GameNativeApi.buildGetRequest(url)

        val result = GameNativeApi.executeRequest(request) { body ->
            parse(JSONObject(body), modernBuild)
        }

        when (result) {
            is ApiResult.Success -> {
                val count = result.data.values.sumOf { it.size }
                if (count == 0) {
                    Timber.tag("DeviceGameStatsService")
                        .w("$tag request succeeded but parsed 0 games - response had no game data (wrong route or no data?)")
                } else {
                    Timber.tag("DeviceGameStatsService").d("Parsed $tag stats for $count games")
                }
                result.data
            }
            is ApiResult.HttpError -> {
                Timber.tag("DeviceGameStatsService").w("$tag HTTP ${result.code}: ${result.message}")
                null
            }
            is ApiResult.NetworkError -> {
                Timber.tag("DeviceGameStatsService").e(result.exception, "Network error fetching $tag game stats")
                null
            }
        }
    }

    private fun parse(json: JSONObject, modernBuild: Boolean): Map<GameSource, Map<String, DeviceGameStats>> {
        val games = (if (modernBuild) json.optJSONObject("games_modern") else null)
            ?: json.optJSONObject("games")
            ?: return emptyMap()
        val output = mutableMapOf<GameSource, Map<String, DeviceGameStats>>()

        for (platformKey in games.keys()) {
            val source = runCatching { GameSource.valueOf(platformKey) }.getOrNull() ?: continue
            val platformGames = games.optJSONObject(platformKey) ?: continue

            val stats = mutableMapOf<String, DeviceGameStats>()
            for (gameName in platformGames.keys()) {
                val arr = platformGames.optJSONArray(gameName) ?: continue
                stats[gameName] = DeviceGameStats(
                    successfulRuns = arr.optInt(0, 0),
                    medianFps = arr.optInt(1, 0),
                    fiveStarReviews = arr.optInt(2, 0),
                    medianSessionSec = arr.optInt(3, 0),
                )
            }
            output[source] = stats
        }

        return output
    }
}
