package app.gamenative.data

import android.content.Context
import app.gamenative.NetworkMonitor
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import java.io.File
import java.time.Instant
import okhttp3.Request
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
    val videoUrl: String = "",
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
    const val TEMPLATE_VIDEO_CARD = "video_card"

    private const val VIDEO_DIR = "boot_ads"
    private const val MAX_VIDEO_BYTES = 64L * 1024L * 1024L

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
        if (ad.template != TEMPLATE_CTA_CARD && ad.template != TEMPLATE_VIDEO_CARD) return null
        if (ad.imageUrl.isEmpty()) return null
        if (!isWithinWindow(ad)) return null
        if (ad.maxShowsPerDay > 0 && showsToday(ad.campaignId) >= ad.maxShowsPerDay) return null
        return ad
    }

    /** The pre-downloaded video for this campaign, or null so the UI falls back to the still card. */
    fun cachedVideoFile(context: Context, ad: BootAdItem): File? {
        if (ad.videoUrl.isEmpty()) return null
        val file = File(File(context.cacheDir, VIDEO_DIR), "${ad.campaignId}.mp4")
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Blocking; call from IO alongside the hero fetch. Downloads the campaign video once, on
     * WiFi/Ethernet only, and prunes videos of past campaigns. Boot itself never calls this —
     * it plays the cached file or shows the still card.
     */
    fun prefetchVideo(context: Context, ad: BootAdItem?) {
        val dir = File(context.cacheDir, VIDEO_DIR)
        val keep = ad?.let { "${it.campaignId}.mp4" }
        dir.listFiles()?.forEach { if (it.name != keep) it.delete() }

        if (ad == null || ad.template != TEMPLATE_VIDEO_CARD || ad.videoUrl.isEmpty()) return
        if (!NetworkMonitor.hasWifiOrEthernet.value) return
        val target = File(dir, keep!!)
        if (target.exists() && target.length() > 0) return

        dir.mkdirs()
        val tmp = File(dir, "$keep.tmp")
        try {
            val request = Request.Builder().url(ad.videoUrl).build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                if (body.contentLength() > MAX_VIDEO_BYTES) {
                    Timber.tag("BootAdRepo").d("Boot ad video too large: %d", body.contentLength())
                    return
                }
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            Timber.tag("BootAdRepo").d(e, "Boot ad video prefetch failed")
            tmp.delete()
        }
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
