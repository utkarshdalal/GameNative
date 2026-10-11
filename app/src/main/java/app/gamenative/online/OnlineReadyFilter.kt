package app.gamenative.online

import android.content.Context
import app.gamenative.data.LibraryItem
import app.gamenative.data.GameSource

/**
 * Games shown under the Online Ready library tab.
 */
object OnlineReadyFilter {
    fun isOnlineReady(context: Context, item: LibraryItem): Boolean {
        if (item.gameSource != GameSource.STEAM) return false
        val steamId = item.appId.removePrefix("STEAM_").ifBlank { item.gameId.toString() }
        val report = OnlineFeedbackStore.get(context, steamId, store = "steam")
        if (report?.worked == true) return true
        val info = OnlineMatrixRepository.get(context, steamId, store = "steam")
        return info?.status == OnlineStatus.ONLINE_OK
    }

    fun debugReportLine(context: Context, steamAppId: Int): String {
        val appId = steamAppId.toString()
        val info = OnlineCapabilityDetector.resolveSteam(context, steamAppId)
        val report = OnlineFeedbackStore.get(context, appId)
        val profile = InstallProfileStore.get(context, InstallProfileApplier.containerIdForSteam(steamAppId))
        return buildString {
            append("online_debug app=$appId")
            append(" status=${info.status.storageKey}")
            append(" capability=${info.capability.storageKey}")
            append(" profile=${profile?.storageKey ?: "none"}")
            if (report != null) {
                append(" last=${report.toDebugLine()}")
            }
        }
    }
}
