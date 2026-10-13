package app.gamenative.online

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem

/**
 * Games shown under the Online Ready library tab / Online library filter.
 * Includes Online OK, Flaky, and Untested (not Blocked / Offline-only).
 */
object OnlineReadyFilter {
    fun isOnlineReady(context: Context, item: LibraryItem): Boolean {
        return offersOnlineInstall(context, item)
    }

    /** Filter: titles that should show Online/Offline install choice. */
    fun offersOnlineInstall(context: Context, item: LibraryItem): Boolean {
        if (item.gameSource != GameSource.STEAM) return false
        val steamId = item.appId.removePrefix("STEAM_").ifBlank { item.gameId.toString() }
        val report = OnlineFeedbackStore.get(context, steamId, store = "steam")
        if (report?.worked == true) return true
        val appIdInt = steamId.toIntOrNull() ?: return false
        return OnlineCapabilityDetector.offersOnlineInstall(context, appIdInt)
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
