package app.gamenative.data

import android.content.Context
import app.gamenative.PrefManager
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Server-driven sponsor card rendered over the booting splash. */
@Serializable
data class BootAdItem(
    val campaignId: String,
    val template: String = "cta_card",
    val imageUrl: String = "",
    val screenshots: List<String> = emptyList(),
    // Advertiser-supplied locale -> localized copy; resolved on-device.
    val title: Map<String, String> = emptyMap(),
    val body: Map<String, String> = emptyMap(),
    val action: FeaturedAction? = null,
    val appId: Int? = null,
    val maxShowsPerDay: Int = 5,
    val startsAt: String? = null,
    val endsAt: String? = null,
)

fun BootAdItem.localizedTitle(context: Context): String = title.forLocale(context) ?: ""

fun BootAdItem.localizedBody(context: Context): String = body.forLocale(context) ?: ""

object BootAdRepository {

    const val TEMPLATE_CTA_CARD = "cta_card"

    private val json = Json { ignoreUnknownKeys = true }

    /** Persist the latest payload from a successful hero fetch; null clears an ended campaign. */
    fun store(ad: BootAdItem?) {
        PrefManager.bootAdCacheJson = if (ad == null) "" else json.encodeToString(ad)
    }

    /**
     * The ad to render on the next boot, or null for the plain splash. Served purely from the
     * disk cache — boot never fetches. Null when the user disabled sponsored loading screens,
     * the campaign window is over, or today's frequency cap is spent.
     */
    fun getActiveAd(): BootAdItem? {
        if (!PrefManager.bootScreenAdsEnabled) return null
        val cached = PrefManager.bootAdCacheJson
        if (cached.isEmpty()) return null
        val ad = runCatching { json.decodeFromString<BootAdItem>(cached) }.getOrNull() ?: return null
        if (ad.template != TEMPLATE_CTA_CARD) return null
        if (ad.imageUrl.isEmpty()) return null
        if (!isWithinWindow(ad)) return null
        if (ad.maxShowsPerDay > 0 && showsToday(ad.campaignId) >= ad.maxShowsPerDay) return null
        return ad
    }

    fun recordShown(campaignId: String) {
        PrefManager.bootAdShowCount = "$campaignId:${daySeed()}:${showsToday(campaignId) + 1}"
    }

    private fun isWithinWindow(ad: BootAdItem): Boolean {
        val now = Instant.now()
        return try {
            (ad.startsAt == null || !now.isBefore(Instant.parse(ad.startsAt))) &&
                (ad.endsAt == null || now.isBefore(Instant.parse(ad.endsAt)))
        } catch (e: Exception) {
            Timber.tag("BootAdRepo").d(e, "Unparseable campaign window, treating as inactive")
            false
        }
    }

    private fun showsToday(campaignId: String): Int {
        val parts = PrefManager.bootAdShowCount.split(":")
        if (parts.size != 3) return 0
        if (parts[0] != campaignId || parts[1] != daySeed().toString()) return 0
        return parts[2].toIntOrNull() ?: 0
    }

    private fun daySeed(): Long = System.currentTimeMillis() / 86_400_000L
}
