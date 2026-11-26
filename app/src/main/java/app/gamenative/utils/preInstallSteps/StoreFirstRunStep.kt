package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.store.StoreAdapters
import app.gamenative.service.store.StoreGames
import com.winlator.container.Container
import java.io.File

/**
 * Runs a [app.gamenative.service.store.StoreAdapter]'s one-time setup (for EA, the installer's
 * touchup) inside the prefix. Tracked by the game-dir marker and a prefix stamp, so a new
 * prefix runs it again.
 */
object StoreFirstRunStep : PreInstallStep {
    override val marker: Marker = Marker.STORE_FIRST_RUN_DONE

    override val viaShell: Boolean get() = false

    private fun prefixStamp(container: Container): File = File(container.rootDir, ".wine/${Marker.STORE_FIRST_RUN_DONE.fileName}")

    override fun appliesTo(container: Container, gameSource: GameSource, gameDirPath: String): Boolean =
        StoreAdapters.of(gameSource) != null &&
            (!MarkerUtils.hasMarker(gameDirPath, marker) || !prefixStamp(container).exists())

    override fun buildCommand(container: Container, appId: String, gameSource: GameSource, gameDir: File, gameDirPath: String): String? {
        val game = gameSource.idOf(appId).toIntOrNull()?.let { StoreGames.game(it) } ?: return null
        val command = StoreAdapters.of(gameSource)?.firstRunCommand(container, game, gameDir, StoreGames.windowsDir(container, gameDirPath))
        runCatching { prefixStamp(container).createNewFile() }
        if (command == null) MarkerUtils.addMarker(gameDirPath, marker)
        return command
    }
}
