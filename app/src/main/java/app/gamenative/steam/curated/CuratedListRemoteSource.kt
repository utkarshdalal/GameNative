package app.gamenative.steam.curated

import androidx.annotation.VisibleForTesting
import app.gamenative.utils.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import timber.log.Timber

internal object CuratedListRemoteSource {

    private const val LIST_VERSION = 1
    private const val REVIEW_TYPE_RECOMMENDED = "recommended"

    private val json = Json { ignoreUnknownKeys = true }
    private val listUrl =
        "https://downloads.gamenative.app/curated_lists/steam/four_three_games.json".toHttpUrl()

    @Serializable
    private data class ListDocument(
        val version: Int,
        val curatorClanId: Long,
        val reviewType: String,
        val totalCount: Int,
        val appIds: List<Int>,
    )

    suspend fun fetch(): Set<Int>? = fetch(Net.http, listUrl)

    @VisibleForTesting
    internal suspend fun fetch(client: Call.Factory, url: HttpUrl): Set<Int>? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag("CuratedListRemote").w("HTTP %d; keeping cached list", response.code)
                        null
                    } else {
                        parse(response.body.string())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("CuratedListRemote").w(e, "Fetch failed; keeping cached list")
                null
            }
        }

    @VisibleForTesting
    internal fun parse(body: String): Set<Int>? {
        val document = try {
            json.decodeFromString<ListDocument>(body)
        } catch (e: Exception) {
            Timber.tag("CuratedListRemote").w(e, "Invalid list document; rejecting")
            return null
        }
        if (
            document.version != LIST_VERSION ||
            document.curatorClanId != CURATOR_CLAN_ID_4_3 ||
            document.reviewType != REVIEW_TYPE_RECOMMENDED
        ) {
            Timber.tag("CuratedListRemote").w("Unexpected list metadata; rejecting")
            return null
        }
        val appIds = document.appIds.toSet()
        if (
            appIds.isEmpty() ||
            appIds.size != document.appIds.size ||
            appIds.size != document.totalCount ||
            appIds.any { it <= 0 }
        ) {
            Timber.tag("CuratedListRemote").w("List empty or count mismatch; rejecting")
            return null
        }
        return appIds
    }
}
