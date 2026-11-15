package app.gamenative.optimize

import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import com.posthog.PostHog
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The Optimize funnel, sent to our own API in small batches: which cards were shown and answered, which steps went
 * on and how they were judged. Fire and forget: a batch the server did not take is kept in memory for the next one.
 * Feature telemetry keyed by the device-scoped Optimize id; the PostHog mirror of a few events follows the analytics
 * setting.
 */
object OptimizeEvents {
    const val CARD_SHOWN = "card_shown"
    const val CARD_NOT_NOW = "card_not_now"
    const val CARD_NEVER = "card_never"
    const val CARD_ACCEPTED = "card_accepted"
    const val CAP_ASKED = "cap_asked"
    const val CAP_ACCEPTED = "cap_accepted"
    const val CAP_DECLINED = "cap_declined"
    const val SIGNIN_REQUIRED = "signin_required"
    const val APPLY_OK = "apply_ok"
    const val APPLY_FAILED = "apply_failed"
    const val JUDGED_HELPED = "judged_helped"
    const val JUDGED_NO_CHANGE = "judged_no_change"
    const val JUDGED_WORSE = "judged_worse"
    const val RESTORED = "restored"
    const val EXHAUSTED = "exhausted"
    const val DONE = "done"

    /** Mirrored to PostHog as optimize_<name> when usage analytics is on. */
    private val MIRRORED = setOf(CARD_SHOWN, CARD_ACCEPTED, JUDGED_HELPED, JUDGED_NO_CHANGE, JUDGED_WORSE)

    private const val MAX_QUEUE = 200
    private const val BATCH_DELAY_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val queue = ArrayDeque<JSONObject>()
    private var flushJob: Job? = null

    /** The judged_ event for a judge result, or null for results that are not a comparison. */
    fun judged(result: String): String? = when (result) {
        OptimizeJudge.HELPED -> JUDGED_HELPED
        OptimizeJudge.NO_CHANGE -> JUDGED_NO_CHANGE
        OptimizeJudge.WORSE, OptimizeJudge.CRASH -> JUDGED_WORSE
        else -> null
    }

    /** One event as the server takes it; [game] holds store, store_id, app_id and name. */
    fun event(
        name: String,
        game: JSONObject,
        profile: OptimizeProfile?,
        cls: String?,
        cardKind: String?,
        rungId: String?,
        rulesVersion: String?,
        nowMillis: Long,
        unverified: Boolean = false,
        extra: JSONObject? = null,
    ): JSONObject = JSONObject().apply {
        put("event", name)
        put("game", game)
        put("profile", profile?.wireId ?: JSONObject.NULL)
        put("class", cls ?: JSONObject.NULL)
        put("card_kind", cardKind ?: JSONObject.NULL)
        put("rung_id", rungId ?: JSONObject.NULL)
        put("rules_version", rulesVersion ?: JSONObject.NULL)
        put("client_ts", Instant.ofEpochMilli(nowMillis).toString())
        if (unverified) put("unverified", true)
        extra?.keys()?.forEach { key -> if (!has(key)) put(key, extra.get(key)) }
    }

    /**
     * The load signals a judged_ event carries: GPU work per frame before and after, the session's ceiling, where its
     * hot thread ran, whether it tessellates, and [gpuWorkScaling] for a resolution step.
     */
    fun judgedExtra(before: Metrics?, after: Metrics, gpuWorkScaling: Double? = null): JSONObject = JSONObject().apply {
        before?.workPerFrame()?.let { put("gpu_work_per_frame_before", Math.round(it * 100) / 100.0) }
        after.workPerFrame()?.let { put("gpu_work_per_frame_after", Math.round(it * 100) / 100.0) }
        after.gpuBusyP95?.let { put("gpu_busy_p95", it) }
        after.hotThreadP95?.let { put("hot_thread_p95", it) }
        after.fpsCeilingReason?.let { put("fps_ceiling_reason", it) }
        after.hotThreadCoreShare?.let { share -> put("hot_thread_core_share", JSONObject().apply { share.forEach { (k, v) -> put(k, v) } }) }
        after.tessellationPipelines?.let { put("tessellation_pipelines", it) }
        gpuWorkScaling?.let { put("gpu_work_scaling", it) }
    }

    private val MIRRORED_EXTRA = listOf(
        "gpu_work_per_frame_before", "gpu_work_per_frame_after", "gpu_busy_p95", "hot_thread_p95", "fps_ceiling_reason",
        "tessellation_pipelines", "gpu_work_scaling",
    )

    /** Queues [event] for the next batch; [device] is the device block every batch carries. */
    fun record(event: JSONObject, device: () -> JSONObject) {
        mirror(event)
        synchronized(lock) {
            queue.addLast(event)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            if (flushJob?.isActive == true) return
            flushJob = scope.launch {
                delay(BATCH_DELAY_MS)
                // Events queued while a batch was on its way go out in the next one.
                while (flush(device) && synchronized(lock) { queue.isNotEmpty() }) Unit
            }
        }
    }

    /** Sends what is queued; true when the server took it. */
    private suspend fun flush(device: () -> JSONObject): Boolean {
        val batch = synchronized(lock) { queue.toList() }
        if (batch.isEmpty()) return false
        val body = JSONObject().apply {
            put("schema", 1)
            put("app_version_code", BuildConfig.VERSION_CODE)
            put("app_version", BuildConfig.VERSION_NAME)
            put("device", device())
            put("events", JSONArray().apply { batch.forEach { put(it) } })
        }
        if (!OptimizeApi.postEvents(body)) return false
        synchronized(lock) { repeat(minOf(batch.size, queue.size)) { queue.removeFirst() } }
        return true
    }

    private fun mirror(event: JSONObject) {
        val name = event.optString("event")
        if (name !in MIRRORED) return
        try {
            if (!PrefManager.usageAnalyticsEnabled) return
            val game = event.optJSONObject("game")
            PostHog.capture(
                event = "optimize_$name",
                properties = buildMap<String, Any> {
                    game?.optStringOrNull("store")?.let { put("game_store", it) }
                    game?.optStringOrNull("name")?.let { put("game_name", it) }
                    event.optStringOrNull("profile")?.let { put("profile", it) }
                    event.optStringOrNull("class")?.let { put("verdict_class", it) }
                    event.optStringOrNull("card_kind")?.let { put("card_kind", it) }
                    event.optStringOrNull("rung_id")?.let { put("rung_id", it) }
                    event.optStringOrNull("rules_version")?.let { put("rules_version", it) }
                    if (event.optBoolean("unverified", false)) put("unverified", true)
                    MIRRORED_EXTRA.forEach { key -> event.opt(key)?.takeIf { it != JSONObject.NULL }?.let { put(key, it) } }
                },
            )
        } catch (e: Exception) {
            Timber.tag("Optimize").w(e, "Mirroring $name to PostHog failed")
        }
    }
}
