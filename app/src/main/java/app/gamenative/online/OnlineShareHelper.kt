package app.gamenative.online

import android.content.Context
import android.content.Intent
import android.os.Build
import app.gamenative.Constants

/**
 * Builds shareable "Online OK on my device" text cards.
 */
object OnlineShareHelper {
    fun buildCardText(
        context: Context,
        steamAppId: Int,
        gameName: String,
    ): String {
        val appId = steamAppId.toString()
        val info = OnlineCapabilityDetector.resolveSteam(context, steamAppId)
        val report = OnlineFeedbackStore.get(context, appId)
        val profile = InstallProfileStore.get(
            context,
            InstallProfileApplier.containerIdForSteam(steamAppId),
        )
        val reportLine = when {
            report == null -> "My report: none yet"
            report.worked -> "My report: ONLINE WORKED"
            else -> "My report: FAILED (${report.failReason?.label ?: "Unknown"})"
        }
        val device = listOfNotNull(
            Build.MANUFACTURER?.replaceFirstChar { it.uppercase() },
            Build.MODEL,
        ).joinToString(" ").ifBlank { "Android device" }

        return buildString {
            appendLine("GameNative Online")
            appendLine(gameName)
            appendLine("Status: ${info.status.badgeLabel}")
            appendLine(reportLine)
            appendLine("Install profile: ${profile?.storageKey ?: "default"}")
            appendLine("Device: $device (Android ${Build.VERSION.RELEASE})")
            appendLine("App ID: $appId")
            appendLine(Constants.Misc.COMPATIBILITY_LINK)
        }.trim()
    }

    fun shareIntent(context: Context, steamAppId: Int, gameName: String): Intent {
        val text = buildCardText(context, steamAppId, gameName)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "GameNative Online — $gameName")
            putExtra(Intent.EXTRA_TEXT, text)
        }.let { Intent.createChooser(it, "Share online status") }
    }

    fun buildOnlineReadySummary(context: Context, gameNamesByAppId: Map<String, String>): String {
        val worked = OnlineFeedbackStore.workedSteamAppIds(context)
        val matrix = OnlineMatrixRepository.load(context)
        val matrixOk = matrix.values
            .filter { it.status == OnlineStatus.ONLINE_OK }
            .map { it.appId }
            .toSet()
        val ids = (worked + matrixOk).sorted()
        val device = listOfNotNull(
            Build.MANUFACTURER?.replaceFirstChar { it.uppercase() },
            Build.MODEL,
        ).joinToString(" ")

        return buildString {
            appendLine("GameNative Online — Online Ready on my device")
            appendLine("Device: $device (Android ${Build.VERSION.RELEASE})")
            appendLine("Titles: ${ids.size}")
            appendLine()
            ids.forEach { id ->
                val name = gameNamesByAppId[id]
                    ?: matrix["steam:$id"]?.notes?.substringBefore("—")?.trim()
                    ?: "App $id"
                val source = when {
                    id in worked -> "my report"
                    else -> "matrix ONLINE OK"
                }
                appendLine("• $name ($id) — $source")
            }
            appendLine()
            appendLine(Constants.Misc.COMPATIBILITY_LINK)
        }.trim()
    }
}
