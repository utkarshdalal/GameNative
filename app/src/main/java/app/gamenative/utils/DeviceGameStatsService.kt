package app.gamenative.utils

import app.gamenative.data.CommunityRatingDistribution
import app.gamenative.data.GameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber

/**
 * Fetches game compatibility stats from the GameNative API.
 *
 * Device, exact GPU and GPU-family endpoints share this compact response shape:
 *
 *   { "games": { "STEAM": { "Balatro": [n, mfps, s5, secs], ... }, "EPIC": {…}, ... } }
 *
 * where n = successful runs, mfps = median fps, s5 = 5-star reviews, secs = median session length.
 * New responses append s1, s2, s3, s4 (in that order). These are historical rating counts,
 * not a recent/version-specific sample; old four-field rows have no complete histogram.
 * The server filters modern/legacy results based on the modernBuild query param and returns them
 * under "games" (we also honor a "games_modern" key if a future response provides one).
 */
object DeviceGameStatsService {

    // Hardcoded to production to match GameCompatibilityService (the "Compatible" badge source).
    // GameNativeApi.BASE_URL points at http://10.0.2.2:8787 in debug builds, which is unreachable
    // from a physical device.
    private const val DEVICE_URL = "https://api.gamenative.app/api/device-game-stats"
    private const val GPU_URL = "https://api.gamenative.app/api/gpu-game-stats"
    private const val FAMILY_URL = "https://api.gamenative.app/api/gpu-family-game-stats"

    @Serializable
    data class DeviceGameStats(
        val successfulRuns: Int,
        val medianFps: Int,
        val fiveStarReviews: Int,
        val medianSessionSec: Int,
        val ratings: CommunityRatingDistribution? = null,
    )

    suspend fun fetchForFamily(gpuName: String, modernBuild: Boolean): Map<GameSource, Map<String, DeviceGameStats>>? {
        val url = FAMILY_URL.toHttpUrl().newBuilder()
            .addQueryParameter("gpuName", gpuName)
            .addQueryParameter("modernBuild", modernBuild.toString())
            .build().toString()
        return fetch(url, modernBuild, "family")
    }

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

    internal suspend fun fetch(
        url: String,
        modernBuild: Boolean,
        tag: String,
        client: OkHttpClient = CompatibilityHttp.client,
        timeoutMillis: Long = 45_000L,
    ): Map<GameSource, Map<String, DeviceGameStats>>? = withContext(Dispatchers.IO) {
        Timber.tag("DeviceGameStatsService").d("Fetching $tag game stats: $url")

        val request = okhttp3.Request.Builder().url(url).get().build()
        var retryAt = 0L
        try {
            val result = withTimeoutOrNull(timeoutMillis) {
                val started = System.currentTimeMillis()
                repeat(3) { attempt ->
                    val response = CompatibilityHttp.execute(client.newCall(request))
                    if (response.code == 429) {
                        val retryAfterMillis = CompatibilityRetryPolicy.withJitter(
                            CompatibilityRetryPolicy.retryAfterMillis(response.retryAfter),
                        )
                        retryAt = CompatibilityRetryPolicy.deadline(System.currentTimeMillis(), retryAfterMillis)
                        if (attempt == 2 || retryAfterMillis >= timeoutMillis - (System.currentTimeMillis() - started)) {
                            throw GameCompatibilityService.RateLimited(retryAfterMillis)
                        }
                        kotlinx.coroutines.delay(retryAfterMillis)
                    } else {
                        if (!response.isSuccessful) {
                            Timber.tag("DeviceGameStatsService").w("$tag HTTP ${response.code}")
                            return@withTimeoutOrNull null
                        }
                        val json = JSONObject(response.body ?: return@withTimeoutOrNull null)
                        require(json.optJSONObject("games") != null || (modernBuild && json.optJSONObject("games_modern") != null)) {
                            "Missing game stats payload"
                        }
                        return@withTimeoutOrNull parse(json, modernBuild)
                    }
                }
                null
            }
            if (result == null && retryAt > System.currentTimeMillis()) {
                throw GameCompatibilityService.RateLimited(retryAt - System.currentTimeMillis())
            }
            result
        } catch (error: GameCompatibilityService.RateLimited) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag("DeviceGameStatsService").w(error, "Failed to load $tag stats")
            null
        }
    }

    internal fun parse(json: JSONObject, modernBuild: Boolean): Map<GameSource, Map<String, DeviceGameStats>> {
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
                    ratings = if (arr.length() >= 8 && listOf(2, 4, 5, 6, 7).all { !arr.isNull(it) }) {
                        CommunityRatingDistribution(
                            oneStar = arr.optInt(4).coerceAtLeast(0),
                            twoStar = arr.optInt(5).coerceAtLeast(0),
                            threeStar = arr.optInt(6).coerceAtLeast(0),
                            fourStar = arr.optInt(7).coerceAtLeast(0),
                            fiveStar = arr.optInt(2).coerceAtLeast(0),
                        )
                    } else {
                        null
                    },
                )
            }
            output[source] = stats
        }

        return output
    }
}
