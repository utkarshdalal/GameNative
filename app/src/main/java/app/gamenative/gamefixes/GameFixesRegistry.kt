package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

object GameFixesRegistry {
    private const val GAME_DRIVE_LETTER = "A"

    private val fixes: Map<Pair<GameSource, String>, GameFix> = mapOf(
        GameSource.GOG to "1454315831" to GOG_Fix_1454315831,
        GameSource.GOG to "1998527297" to GOG_Fix_1998527297,
        GameSource.GOG to "1454587428" to GOG_Fix_1454587428,
    )

    fun applyFor(context: Context, appId: String) {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return
        val fix = fixes[source to gameId] ?: return
        val (installPath, installPathWindows) = resolvePaths(context, source, gameId) ?: return
        fix.apply(context, gameId, installPath, installPathWindows)
    }

    private fun resolvePaths(context: Context, source: GameSource, gameId: String): Pair<String, String>? {
        return when (source) {
            GameSource.GOG -> {
                val game = runBlocking(Dispatchers.IO) { GOGService.getGOGGameOf(gameId) } ?: return null
                if (!game.isInstalled) return null
                val path = game.installPath.ifEmpty { GOGConstants.getGameInstallPath(game.title) }
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            else -> null
        }
    }
}
