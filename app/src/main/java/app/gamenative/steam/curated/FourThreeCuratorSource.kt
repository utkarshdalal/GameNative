package app.gamenative.steam.curated

import androidx.annotation.VisibleForTesting
import app.gamenative.utils.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber

internal object FourThreeCuratorSource {

    private val endpoint =
        "https://store.steampowered.com/curator/$CURATOR_CLAN_ID_4_3/ajaxgetfilteredrecommendations/".toHttpUrl()

    private const val PAGE_SIZE = 1000

    private val appIdRegex = Regex("""data-ds-appid="(\d+)"""")
    private val unsupportedReviewTypeRegex = Regex("""color_(?:informational|not_recommended)""")

    @VisibleForTesting
    internal data class Page(val appIds: Set<Int>, val totalCount: Int)

    suspend fun fetch(): Set<Int>? = fetch(Net.http, endpoint)

    @VisibleForTesting
    internal suspend fun fetch(
        client: Call.Factory,
        endpoint: HttpUrl,
        pageSize: Int = PAGE_SIZE,
    ): Set<Int>? = withContext(Dispatchers.IO) {
        val appIds = sortedSetOf<Int>()
        var expectedCount: Int? = null
        var start = 0

        while (true) {
            val page = fetchPage(client, endpoint, pageSize, start) ?: return@withContext null
            val pageCount = page.totalCount
            if (expectedCount == null) {
                expectedCount = pageCount
            } else if (pageCount != expectedCount) {
                Timber.tag("FourThreeCurator").w("Curator result count changed while fetching; keeping cached list")
                return@withContext null
            }

            val previousSize = appIds.size
            appIds.addAll(page.appIds)
            val totalCount = expectedCount
            if (appIds.size == totalCount) break
            if (appIds.size > totalCount || page.appIds.isEmpty() || appIds.size == previousSize) {
                Timber.tag("FourThreeCurator").w(
                    "Collected %d app IDs but expected %d; keeping cached list",
                    appIds.size,
                    totalCount,
                )
                return@withContext null
            }

            start += page.appIds.size
        }

        appIds.takeIf { it.isNotEmpty() }
    }

    private fun fetchPage(
        client: Call.Factory,
        endpoint: HttpUrl,
        pageSize: Int,
        start: Int,
    ): Page? {
        return try {
            val url = endpoint.newBuilder()
                .addQueryParameter("clanid", CURATOR_CLAN_ID_4_3.toString())
                .addQueryParameter("count", pageSize.toString())
                .addQueryParameter("start", start.toString())
                .addQueryParameter("curations", "0")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) GameNative")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("FourThreeCurator").w("HTTP %d; keeping cached list", response.code)
                    return null
                }
                parse(response.body.string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("FourThreeCurator").w(e, "Fetch failed; keeping cached list")
            null
        }
    }

    @VisibleForTesting
    internal fun parse(body: String): Page? {
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            Timber.tag("FourThreeCurator").w(e, "Response is not JSON; rejecting")
            return null
        }
        if ((root["success"] as? JsonPrimitive)?.intOrNull != 1) {
            Timber.tag("FourThreeCurator").w("Response success != 1; rejecting")
            return null
        }

        val html = (root["results_html"] as? JsonPrimitive)?.content ?: return null
        if (unsupportedReviewTypeRegex.containsMatchIn(html)) {
            Timber.tag("FourThreeCurator").w("Response contained non-recommended entries; rejecting")
            return null
        }
        val appIds = appIdRegex.findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { appId -> appId > 0 } }
            .toSortedSet()
        val totalCount = (root["total_count"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it >= 0 }
            ?: return null
        return Page(appIds, totalCount)
    }
}
