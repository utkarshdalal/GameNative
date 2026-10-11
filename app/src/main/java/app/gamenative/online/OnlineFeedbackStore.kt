package app.gamenative.online

import android.content.Context
import org.json.JSONObject

/**
 * Persists local "Did online work?" answers per game.
 */
object OnlineFeedbackStore {
    private const val PREFS = "gamenative_online_feedback"
    private const val KEY_PREFIX = "report_"
    private const val ASKED_PREFIX = "asked_"

    fun save(context: Context, report: OnlineSessionReport) {
        val json = JSONObject()
            .put("appId", report.appId)
            .put("store", report.store)
            .put("worked", report.worked)
            .put("failReason", report.failReason?.storageKey)
            .put("notes", report.notes)
            .put("reportedAtEpochMs", report.reportedAtEpochMs)
            .toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + key(report.store, report.appId), json)
            .putLong(ASKED_PREFIX + key(report.store, report.appId), report.reportedAtEpochMs)
            .apply()
    }

    fun get(context: Context, appId: String, store: String = "steam"): OnlineSessionReport? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + key(store, appId), null)
            ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            OnlineSessionReport(
                appId = obj.optString("appId", appId),
                store = obj.optString("store", store),
                worked = obj.optBoolean("worked", false),
                failReason = OnlineFailReason.fromStorageKey(obj.optString("failReason")),
                notes = obj.optString("notes").takeIf { it.isNotBlank() },
                reportedAtEpochMs = obj.optLong("reportedAtEpochMs", 0L),
            )
        }.getOrNull()
    }

    fun markAsked(context: Context, appId: String, store: String = "steam") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(ASKED_PREFIX + key(store, appId), System.currentTimeMillis())
            .apply()
    }

    fun lastAskedAt(context: Context, appId: String, store: String = "steam"): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(ASKED_PREFIX + key(store, appId), 0L)
    }

    fun shouldAsk(
        context: Context,
        appId: String,
        store: String = "steam",
        cooldownMs: Long = ASK_COOLDOWN_MS,
    ): Boolean {
        val last = lastAskedAt(context, appId, store)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= cooldownMs
    }

    /** App IDs the user marked as online worked (steam). */
    fun workedSteamAppIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            runCatching {
                val obj = JSONObject(raw)
                if (obj.optBoolean("worked", false) && obj.optString("store", "steam") == "steam") {
                    obj.optString("appId")
                } else {
                    null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }.toSet()
    }

    private fun key(store: String, appId: String) = "$store:$appId"

    const val ASK_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
}
