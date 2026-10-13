package app.gamenative.online

import android.content.Context
import app.gamenative.data.StoreGameDetails
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves whether a game should offer Online/Offline install and which badge to show.
 *
 * Priority: matrix → store tags/categories (incl. cached Steam store signals) → offline-only.
 * Tags never invent ONLINE_OK (Phase 4: reduce false positives).
 */
object OnlineCapabilityDetector {
    private val ONLINE_TAG_MARKERS = listOf(
        "multi-player",
        "multiplayer",
        "online pvp",
        "online co-op",
        "online coop",
        "online multiplayer",
        "cross-platform multiplayer",
        "massively multiplayer",
        "mmo",
        "co-op",
        "coop",
        "pvp",
    )

    fun resolveSteam(
        context: Context,
        steamAppId: Int,
        storeDetails: StoreGameDetails? = null,
    ): OnlineGameInfo {
        val appId = steamAppId.toString()
        val base = OnlineMatrixRepository.get(context, appId, store = "steam")
            ?: run {
                val tags = buildTagSet(context, steamAppId, storeDetails)
                if (hasOnlineTags(tags)) {
                    OnlineGameInfo.untestedOnline(appId)
                } else {
                    OnlineGameInfo.offlineOnly(appId)
                }
            }
        return applyLocalReportAdjustments(context, base)
    }

    /**
     * Like [resolveSteam], but fetches Steam store categories when local tags/matrix are empty.
     * Use from install dialog / game details so Online install appears for new multiplayer titles.
     */
    suspend fun resolveSteamRefreshing(
        context: Context,
        steamAppId: Int,
        storeDetails: StoreGameDetails? = null,
    ): OnlineGameInfo = withContext(Dispatchers.IO) {
        val appId = steamAppId.toString()
        OnlineMatrixRepository.get(context, appId, store = "steam")?.let {
            return@withContext applyLocalReportAdjustments(context, it)
        }

        var tags = buildTagSet(context, steamAppId, storeDetails)
        if (!hasOnlineTags(tags)) {
            tags = tags + OnlineStoreSignals.tagsForSteamApp(context, steamAppId)
        }
        val base = if (hasOnlineTags(tags)) {
            OnlineGameInfo.untestedOnline(appId)
        } else {
            OnlineGameInfo.offlineOnly(appId)
        }
        applyLocalReportAdjustments(context, base)
    }

    /** True when the game should offer Online install (not blocked / offline-only). */
    fun offersOnlineInstall(
        context: Context,
        steamAppId: Int,
        storeDetails: StoreGameDetails? = null,
    ): Boolean {
        val info = resolveSteam(context, steamAppId, storeDetails)
        return info.showInstallProfileChoice
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

    private fun buildTagSet(
        context: Context,
        steamAppId: Int,
        storeDetails: StoreGameDetails?,
    ): List<String> {
        val fromDetails = storeDetails?.tags.orEmpty()
        val fromCache = OnlineStoreSignals.cachedTags(context, steamAppId).orEmpty()
        return (fromDetails + fromCache).distinct()
    }
}
