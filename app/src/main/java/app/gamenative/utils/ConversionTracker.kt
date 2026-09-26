package app.gamenative.utils

import android.os.SystemClock
import app.gamenative.PrefManager
import app.gamenative.data.BootAdItem
import app.gamenative.data.BootAdRepository
import com.posthog.PostHog
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

object ConversionTracker {

    private const val RETURN_WINDOW_MS = 30L * 60L * 1000L
    private const val CAMPAIGN_CLICK_TTL_MS = 14L * 24L * 60L * 60L * 1000L
    private const val MAX_CAMPAIGN_CLICKS = 20

    private val json = Json { ignoreUnknownKeys = true }

    fun featuredConversion(
        campaignId: String,
        actionType: String,
        appId: Int?,
        source: String,
        extras: Map<String, Any> = emptyMap(),
    ) {
        val properties = mutableMapOf<String, Any>(
            "campaign_id" to campaignId,
            "action_type" to actionType,
            "source" to source,
        )
        appId?.let { properties["app_id"] = it }
        if (PrefManager.usageAnalyticsEnabled) properties.putAll(extras)

        capture("featured_conversion", properties)
    }

    /** Fired when the booting splash hides, with how long the sponsor card was on screen. */
    fun bootAdShown(campaignId: String, dwellSeconds: Long) {
        val properties = mutableMapOf<String, Any>(
            "campaign_id" to campaignId,
            "dwell_seconds" to dwellSeconds,
        )
        if (PrefManager.usageAnalyticsEnabled) properties.putAll(BootAdView.snapshot())
        capture("boot_ad_shown", properties)
    }

    /** House recommendation card counterpart of [bootAdShown]; only with usage analytics on. */
    fun bootRecShown(campaignId: String, dwellSeconds: Long) {
        track(
            "boot_rec_shown",
            mapOf("campaign_id" to campaignId, "dwell_seconds" to dwellSeconds) + BootAdView.snapshot(),
        )
    }

    /** Usage-analytics-gated capture; null values are dropped. */
    fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        if (!PrefManager.usageAnalyticsEnabled) return
        val props = properties.entries
            .filter { it.value != null }
            .associate { it.key to it.value!! }
        runCatching { PostHog.capture(event = event, properties = props) }
            .onFailure { Timber.w(it, "Failed to capture %s", event) }
    }

    private fun capture(event: String, properties: MutableMap<String, Any>) {
        if (PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(event = event, properties = properties)
        } else {
            properties["\$process_person_profile"] = false
            PostHog.capture(
                event = event,
                distinctId = UUID.randomUUID().toString(),
                properties = properties,
            )
        }
    }

    // ---- Post-click attribution (usage analytics only) ----

    @Serializable
    private data class ClickOut(
        val kind: String,
        val id: String,
        val source: String,
        val rank: Int,
        val sid: String,
        val ts: Long,
    )

    @Serializable
    private data class CampaignClick(val appId: Int, val campaignId: String, val source: String, val ts: Long)

    /** Short random token appended to outbound affiliate links so a sale can be joined back to the click event. */
    fun newClickId(): String? =
        if (PrefManager.usageAnalyticsEnabled) UUID.randomUUID().toString().replace("-", "").take(12) else null

    fun rememberClickOut(kind: String, id: String, source: String, rank: Int, sid: String) {
        if (!PrefManager.usageAnalyticsEnabled) return
        runCatching {
            PrefManager.lastClickOutJson = json.encodeToString(
                ClickOut(kind, id, source, rank, sid, System.currentTimeMillis()),
            )
        }.onFailure { Timber.w(it, "Failed to store click-out") }
    }

    /** Called when the app returns to the foreground; emits `recommendation_returned` for a recent click-out. */
    fun onAppForegrounded() {
        if (!PrefManager.usageAnalyticsEnabled) return
        runCatching {
            val raw = PrefManager.lastClickOutJson
            if (raw.isEmpty()) return
            PrefManager.lastClickOutJson = ""
            val click = json.decodeFromString<ClickOut>(raw)
            val away = System.currentTimeMillis() - click.ts
            if (away !in 0..RETURN_WINDOW_MS) return
            track(
                "recommendation_returned",
                mapOf(
                    "kind" to click.kind,
                    "id" to click.id,
                    "source" to click.source,
                    "rank" to click.rank,
                    "sid" to click.sid,
                    "away_ms" to away,
                ),
            )
        }.onFailure { Timber.w(it, "Failed to report click-out return") }
    }

    fun rememberCampaignClick(appId: Int?, campaignId: String, source: String) {
        if (appId == null || !PrefManager.usageAnalyticsEnabled) return
        runCatching {
            val now = System.currentTimeMillis()
            val kept = loadCampaignClicks()
                .filter { it.appId != appId && now - it.ts < CAMPAIGN_CLICK_TTL_MS }
                .takeLast(MAX_CAMPAIGN_CLICKS - 1)
            PrefManager.campaignClicksJson = json.encodeToString(kept + CampaignClick(appId, campaignId, source, now))
        }.onFailure { Timber.w(it, "Failed to store campaign click") }
    }

    /** Campaign properties to attach to install/launch events for an app the user reached via a campaign CTA. */
    fun campaignAttribution(appId: Int?): Map<String, Any> {
        if (appId == null || !PrefManager.usageAnalyticsEnabled) return emptyMap()
        return runCatching {
            val now = System.currentTimeMillis()
            val click = loadCampaignClicks().lastOrNull { it.appId == appId && now - it.ts < CAMPAIGN_CLICK_TTL_MS }
                ?: return emptyMap()
            mapOf(
                "campaign_id" to click.campaignId,
                "campaign_source" to click.source,
                "campaign_click_age_s" to (now - click.ts) / 1000L,
            )
        }.getOrElse {
            Timber.w(it, "Failed to read campaign clicks")
            emptyMap()
        }
    }

    private fun loadCampaignClicks(): List<CampaignClick> {
        val raw = PrefManager.campaignClicksJson
        if (raw.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<CampaignClick>>(raw) }.getOrDefault(emptyList())
    }
}

/** What the booting splash actually rendered for the current boot card; read when the splash hides. */
object BootAdView {
    @Volatile private var campaignId: String? = null
    private var startedAt = 0L
    private var template = ""
    private var sponsored = true
    private var impressionSent = false
    private var cardRenderedAt = -1L
    private var backdrop = "none"
    private var imageFailed = false
    private var ctaType: String? = null
    private var videoFirstFrame = false
    private var videoWatchedMs = 0L
    private var videoPlayingSince = -1L
    private var videoLoops = 0
    private var videoSoundOn = false
    private var quizShown = false
    private var quizAnswered = false
    private var quizTimedOut = false
    private var quizCorrect: Boolean? = null

    @Synchronized
    fun begin(ad: BootAdItem) {
        campaignId = ad.campaignId
        startedAt = SystemClock.elapsedRealtime()
        template = ad.template
        sponsored = ad.sponsored
        impressionSent = false
        cardRenderedAt = -1L
        backdrop = "none"
        imageFailed = false
        ctaType = null
        videoFirstFrame = false
        videoWatchedMs = 0L
        videoPlayingSince = -1L
        videoLoops = 0
        videoSoundOn = false
        quizShown = false
        quizAnswered = false
        quizTimedOut = false
        quizCorrect = null
    }

    /** The card composable mounted with a usable backdrop. Emits `boot_ad_impression` once per boot card. */
    @Synchronized
    fun cardRendered(ad: BootAdItem, backdrop: String, ctaType: String?, quiz: Boolean) {
        if (ad.campaignId != campaignId) return
        if (cardRenderedAt < 0) cardRenderedAt = SystemClock.elapsedRealtime()
        this.backdrop = backdrop
        this.ctaType = ctaType
        if (quiz) quizShown = true
        if (impressionSent) return
        impressionSent = true
        ConversionTracker.track(
            "boot_ad_impression",
            mapOf(
                "campaign_id" to ad.campaignId,
                "sponsored" to ad.sponsored,
                "template" to ad.template,
                "backdrop" to backdrop,
                "has_cta" to (ctaType != null),
                "cta_type" to ctaType,
                "quiz" to quiz,
                "time_to_card_ms" to (cardRenderedAt - startedAt),
            ),
        )
    }

    @Synchronized
    fun imageFailed(id: String) {
        if (id == campaignId) imageFailed = true
    }

    @Synchronized
    fun videoFirstFrame() {
        videoFirstFrame = true
    }

    /** Playback state changes; watched time accumulates across play/pause and is read live in [snapshot]. */
    @Synchronized
    fun videoPlaying(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing) {
            if (videoPlayingSince < 0) videoPlayingSince = now
        } else if (videoPlayingSince >= 0) {
            videoWatchedMs += now - videoPlayingSince
            videoPlayingSince = -1L
        }
    }

    private fun watchedMsNow(): Long =
        videoWatchedMs + if (videoPlayingSince >= 0) SystemClock.elapsedRealtime() - videoPlayingSince else 0L

    @Synchronized
    fun videoLooped() {
        videoLoops++
    }

    @Synchronized
    fun videoSound(on: Boolean) {
        if (on) videoSoundOn = true
    }

    @Synchronized
    fun quizResolved(correct: Boolean, timedOut: Boolean) {
        quizAnswered = !timedOut
        quizTimedOut = timedOut
        quizCorrect = correct
    }

    @Synchronized
    fun snapshot(): Map<String, Any> {
        val props = mutableMapOf<String, Any>(
            "sponsored" to sponsored,
            "template" to template,
            "card_rendered" to (cardRenderedAt >= 0),
            "backdrop" to backdrop,
            "image_failed" to imageFailed,
            "has_cta" to (ctaType != null),
        )
        ctaType?.let { props["cta_type"] = it }
        if (cardRenderedAt >= 0) {
            props["time_to_card_ms"] = cardRenderedAt - startedAt
            props["card_visible_ms"] = SystemClock.elapsedRealtime() - cardRenderedAt
        }
        if (template == BootAdRepository.TEMPLATE_VIDEO_CARD || videoFirstFrame) {
            props["video_started"] = videoFirstFrame
            props["video_watched_ms"] = watchedMsNow()
            props["video_completed"] = videoLoops > 0
            props["video_loops"] = videoLoops
            props["video_sound_on"] = videoSoundOn
        }
        if (quizShown) {
            props["quiz_shown"] = true
            props["quiz_answered"] = quizAnswered
            props["quiz_timed_out"] = quizTimedOut
            quizCorrect?.let { props["quiz_correct"] = it }
        }
        return props
    }
}
