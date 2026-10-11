package app.gamenative.online

import android.content.Context
import app.gamenative.data.StoreGameDetails
import java.util.Locale

/**
 * Resolves whether a game should offer Online/Offline install and which badge to show.
 *
 * Priority: matrix → local user report adjustments → store tags → offline-only.
 * Tags never invent ONLINE_OK (Phase 4: reduce false positives).
 */
object OnlineCapabilityDetector {
    private val ONLINE_TAG_MARKERS = listOf(
        "multi-player",
        "multiplayer",
        "online pvp",
        "online co-op",
        "online coop",
        "co-op",
        "coop",
        "mmo",
        "massively multiplayer",
        "cross-platform multiplayer",
        "online multiplayer",
    )

    fun resolveSteam(
        context: Context,
        steamAppId: Int,
        storeDetails: StoreGameDetails? = null,
    ): OnlineGameInfo {
        val appId = steamAppId.toString()
        val base = OnlineMatrixRepository.get(context, appId, store = "steam")
            ?: run {
                val tagsIndicateOnline = hasOnlineTags(storeDetails?.tags.orEmpty())
                if (tagsIndicateOnline) {
                    OnlineGameInfo.untestedOnline(appId)
                } else {
                    OnlineGameInfo.offlineOnly(appId)
                }
            }
        return applyLocalReportAdjustments(context, base)
    }

    /**
     * User failure reports demote ONLINE_OK → FLAKY.
     * User success reports can lift UNTESTED → OK for the local device view only.
     */
    fun applyLocalReportAdjustments(context: Context, info: OnlineGameInfo): OnlineGameInfo {
        val report = OnlineFeedbackStore.get(context, info.appId, info.store) ?: return info
        return when {
            report.worked && info.status == OnlineStatus.UNTESTED_ONLINE -> info.copy(
                status = OnlineStatus.ONLINE_OK,
                notes = listOfNotNull(info.notes, "Local report: online worked").joinToString(" · "),
            )
            report.worked && info.status == OnlineStatus.ONLINE_FLAKY -> info.copy(
                notes = listOfNotNull(info.notes, "Local report: online worked").joinToString(" · "),
            )
            !report.worked && info.status == OnlineStatus.ONLINE_OK -> info.copy(
                status = OnlineStatus.ONLINE_FLAKY,
                notes = listOfNotNull(
                    info.notes,
                    "Local report failed: ${report.failReason?.label ?: "unknown"}",
                ).joinToString(" · "),
            )
            !report.worked && info.status == OnlineStatus.UNTESTED_ONLINE -> info.copy(
                status = OnlineStatus.ONLINE_FLAKY,
                notes = listOfNotNull(
                    info.notes,
                    "Local report failed: ${report.failReason?.label ?: "unknown"}",
                ).joinToString(" · "),
            )
            else -> info
        }
    }

    fun hasOnlineTags(tags: Collection<String>): Boolean {
        return tags.any { tag ->
            val normalized = tag.lowercase(Locale.ROOT).trim()
            ONLINE_TAG_MARKERS.any { marker -> normalized.contains(marker) }
        }
    }
}
