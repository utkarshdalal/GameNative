package app.gamenative.data

import android.content.Context
import app.gamenative.NetworkMonitor
import app.gamenative.data.gog.GogRecCard
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import java.io.File
import java.time.Instant
import kotlin.random.Random
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
    // Fraction of eligible boots that show the card; the rest get the plain splash.
    val showRate: Double = 1.0,
    // Rotation share among the cached boot campaigns.
    val weight: Double = 1.0,
    // False for the house recommendation card: no Sponsored badge, no ad bookkeeping.
    val sponsored: Boolean = true,
    // House recommendation card only: store price shown in the buy button.
    val priceLabel: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    // template "quiz_card": one is picked at random per boot.
    val questions: List<BootQuizQuestion> = emptyList(),
)

@Serializable
data class BootQuizQuestion(
    // Advertiser-supplied locale -> localized copy; resolved on-device.
    val prompt: Map<String, String> = emptyMap(),
    // Optional monospace block (code snippets); rendered verbatim, not localized.
    val code: String = "",
    val options: List<String> = emptyList(),
    val correctIndex: Int = 0,
    val timerSeconds: Int = 7,
    val winBody: Map<String, String> = emptyMap(),
    val loseBody: Map<String, String> = emptyMap(),
)

fun BootQuizQuestion.localizedPrompt(context: Context): String = prompt.forLocale(context) ?: ""

fun BootQuizQuestion.localizedWinBody(context: Context): String = winBody.forLocale(context) ?: ""

fun BootQuizQuestion.localizedLoseBody(context: Context): String = loseBody.forLocale(context) ?: ""

fun BootQuizQuestion.isPlayable(): Boolean =
    options.size in 2..4 && correctIndex in options.indices && (prompt.isNotEmpty() || code.isNotEmpty())

fun BootAdItem.localizedTitle(context: Context): String = title.forLocale(context) ?: ""

fun BootAdItem.localizedBody(context: Context): String = body.forLocale(context) ?: ""

object BootAdRepository {

    const val TEMPLATE_CTA_CARD = "cta_card"
    const val TEMPLATE_VIDEO_CARD = "video_card"
    const val TEMPLATE_QUIZ_CARD = "quiz_card"

    private const val VIDEO_DIR = "boot_ads"
    private const val MAX_VIDEO_BYTES = 64L * 1024L * 1024L

    private val json = Json { ignoreUnknownKeys = true }

    /** Persist the latest payload from a successful hero fetch; empty clears ended campaigns. */
    fun store(ads: List<BootAdItem>) {
        PrefManager.bootAdCacheJson = if (ads.isEmpty()) "" else json.encodeToString(ads)
    }

    fun cachedAds(): List<BootAdItem> {
        val cached = PrefManager.bootAdCacheJson
        if (cached.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<BootAdItem>>(cached) }.getOrNull()
            // Payloads cached by builds that held a single ad
            ?: runCatching { listOf(json.decodeFromString<BootAdItem>(cached)) }.getOrNull()
            ?: emptyList()
    }

    /**
     * The ad to render on the next boot, or null for the plain splash. Served purely from the
     * disk cache — boot never fetches. Simultaneous campaigns rotate: a weighted random pick
     * among the ones still in window, under their daily cap, and not the one shown on the
     * previous boot. Each campaign's showRate is applied after the pick, so a
     * campaign can keep some boots ad-free without handing them to a competitor.
     */
    fun getActiveAd(): BootAdItem? {
        if (!PrefManager.bootScreenAdsEnabled) return null
        val eligible = cachedAds().filter { isRenderable(it) && isWithinWindow(it) }
            .filter { it.maxShowsPerDay <= 0 || showsToday(it.campaignId) < it.maxShowsPerDay }
        if (eligible.isEmpty()) return null
        val lastShown = PrefManager.bootAdLastShown
        val candidates = eligible.filter { it.campaignId != lastShown }.ifEmpty { eligible }
        val ad = weightedPick(candidates) ?: return null
        if (ad.showRate < 1.0 && Random.nextDouble() >= ad.showRate.coerceAtLeast(0.0)) return null
        return ad
    }

    /**
     * A game recommendation as a house card, for boots with no eligible sponsor. Opt-in via
     * settings. Rotates through the user's personalized Discover cards (plus the day's hero
     * pick, which carries a trailer), never repeating the previous boot's card.
     */
    fun recommendationCard(): BootAdItem? {
        if (!PrefManager.bootScreenRecommendationsEnabled) return null
        val candidates = buildList {
            RecommendationRepository.getCurrentHeroRecommendation()?.let { rec ->
                if (rec.heroImageUrl.isNotEmpty()) add(heroPickCard(rec))
            }
            RecommendationRepository.getRecommendationPool()
                .filter { it.heroImage.isNotEmpty() }
                .forEach { add(discoverCard(it)) }
        }.distinctBy { it.campaignId }
        if (candidates.isEmpty()) return null
        val lastShown = PrefManager.bootAdLastShown
        return candidates.filter { it.campaignId != lastShown }.ifEmpty { candidates }.random()
    }

    private fun heroPickCard(rec: RecommendedGame): BootAdItem {
        // Trailers are streamed (not pre-downloaded like sponsor videos), so WiFi only.
        val trailer = (rec.videos.firstOrNull() ?: rec.videoUrl)
            ?.takeIf { it.isNotEmpty() && NetworkMonitor.hasWifiOrEthernet.value }
        return BootAdItem(
            campaignId = "rec-${rec.id}",
            template = if (trailer != null) TEMPLATE_VIDEO_CARD else TEMPLATE_CTA_CARD,
            imageUrl = rec.heroImageUrl,
            videoUrl = trailer ?: "",
            screenshots = rec.screenshots,
            title = mapOf("en" to rec.name),
            body = mapOf("en" to (rec.becausePlayed?.takeIf { it.isNotBlank() } ?: rec.description)),
            action = rec.affiliateUrl.takeIf { it.isNotEmpty() }?.let {
                FeaturedAction(type = "VISIT", url = it, style = "primary")
            },
            maxShowsPerDay = 0,
            sponsored = false,
            priceLabel = rec.priceLabel,
        )
    }

    private fun discoverCard(card: GogRecCard): BootAdItem {
        // Trailers are streamed, so WiFi only.
        val trailer = app.gamenative.data.gog.GogRecommendationsRepository.cachedTrailer(card.productId)
            ?.takeIf { NetworkMonitor.hasWifiOrEthernet.value }
        return BootAdItem(
        campaignId = "rec-gog-${card.productId}",
        template = if (trailer != null) TEMPLATE_VIDEO_CARD else TEMPLATE_CTA_CARD,
        imageUrl = card.heroImage,
        videoUrl = trailer ?: "",
        title = mapOf("en" to card.title),
        body = mapOf("en" to card.becausePlayed),
        action = card.affiliateUrl.ifEmpty { card.storeUrl }.takeIf { it.isNotEmpty() }?.let {
            FeaturedAction(type = "VISIT", url = it, style = "primary")
        },
        maxShowsPerDay = 0,
        sponsored = false,
        priceLabel = card.priceLabel,
        )
    }

    /** House cards count for rotation only — no impression or cap bookkeeping. */
    fun noteShown(campaignId: String) {
        PrefManager.bootAdLastShown = campaignId
    }

    private fun isRenderable(ad: BootAdItem): Boolean = when (ad.template) {
        // Quiz cards may run without art (plain gradient backdrop); they need questions.
        TEMPLATE_QUIZ_CARD -> ad.questions.any { it.isPlayable() }
        TEMPLATE_CTA_CARD, TEMPLATE_VIDEO_CARD -> ad.imageUrl.isNotEmpty()
        else -> false
    }

    private fun weightedPick(ads: List<BootAdItem>): BootAdItem? {
        if (ads.isEmpty()) return null
        val weights = ads.map { if (it.weight.isFinite() && it.weight > 0) it.weight else 1.0 }
        var roll = Random.nextDouble() * weights.sum()
        ads.forEachIndexed { i, ad ->
            roll -= weights[i]
            if (roll < 0) return ad
        }
        return ads.last()
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
    fun prefetchVideos(context: Context, ads: List<BootAdItem>) {
        val dir = File(context.cacheDir, VIDEO_DIR)
        val videoAds = ads.filter { it.template == TEMPLATE_VIDEO_CARD && it.videoUrl.isNotEmpty() }
        val keep = videoAds.map { "${it.campaignId}.mp4" }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }

        if (videoAds.isEmpty() || !NetworkMonitor.hasWifiOrEthernet.value) return
        dir.mkdirs()
        for (ad in videoAds) {
            val name = "${ad.campaignId}.mp4"
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            val tmp = File(dir, "$name.tmp")
            try {
                val request = Request.Builder().url(ad.videoUrl).build()
                Net.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    if (body.contentLength() > MAX_VIDEO_BYTES) {
                        Timber.tag("BootAdRepo").d("Boot ad video too large: %d", body.contentLength())
                        return@use
                    }
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                if (!tmp.exists() || !tmp.renameTo(target)) tmp.delete()
            } catch (e: Exception) {
                Timber.tag("BootAdRepo").d(e, "Boot ad video prefetch failed")
                tmp.delete()
            }
        }
    }

    fun recordShown(campaignId: String) {
        val day = daySeed().toString()
        val others = showEntries().filter { it[0] != campaignId && it[1] == day }
        val updated = others + listOf(listOf(campaignId, day, (showsToday(campaignId) + 1).toString()))
        PrefManager.bootAdShowCount = updated.joinToString(",") { it.joinToString(":") }
        PrefManager.bootAdLastShown = campaignId
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

    private fun showEntries(): List<List<String>> =
        PrefManager.bootAdShowCount.split(",").map { it.split(":") }.filter { it.size == 3 }

    private fun showsToday(campaignId: String): Int {
        val day = daySeed().toString()
        return showEntries().firstOrNull { it[0] == campaignId && it[1] == day }?.get(2)?.toIntOrNull() ?: 0
    }

    private fun daySeed(): Long = System.currentTimeMillis() / 86_400_000L
}
