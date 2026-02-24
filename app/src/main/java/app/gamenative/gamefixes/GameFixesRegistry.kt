package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object GameFixesRegistry {
    private const val GAME_DRIVE_LETTER = "A"

    private val fixes: Map<Pair<GameSource, String>, GameFix> = listOf(
        GOG_Fix_1454315831,
        GOG_Fix_1454587428,
        GOG_Fix_1458058109,
        STEAM_Fix_22300,
        STEAM_Fix_22380,
        STEAM_Fix_22330,
        EPIC_Fix_b1b4e0b67a044575820cb5e63028dcae,
        EPIC_Fix_dabb52e328834da7bbe99691e374cb84,
        EPIC_Fix_59a0c86d02da42e8ba6444cb171e61bf,
    ).associateBy { it.gameSource to it.gameId }

    fun applyFor(context: Context, appId: String) {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return
        val catalogId = when (source) {
            // EPIC auto-generates the id. so we need the catalog id instead.
            GameSource.EPIC -> {
                val game = EpicService.getEpicGameOf(gameId.toInt()) ?: return
                game.catalogId
            }
            else -> gameId
        }
        Timber.i("GameFixesRegistry: Applying fixes for game: $source $catalogId if available")
        val fix = fixes[source to catalogId] ?: return
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
            GameSource.STEAM -> {
                val path = SteamService.getAppDirPath(gameId.toInt())
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            GameSource.EPIC -> {
                val path = EpicService.getInstallPath(gameId.toInt()) ?: return null
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            else -> null
        }
    }
}
