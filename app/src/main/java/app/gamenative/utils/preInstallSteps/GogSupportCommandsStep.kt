package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import java.io.File

/**
 * Runs Gen 1 (legacy) GOG support_commands: per-game setup executables shipped in the
 * support depot that create registry keys, shortcuts, etc. Galaxy and Heroic run these
 * after install; without them many old titles fail (e.g. CD checks from missing keys).
 */
object GogSupportCommandsStep : PreInstallStep {
    override val marker: Marker = Marker.GOG_SUPPORT_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return gameSource == GameSource.GOG &&
            !MarkerUtils.hasMarker(gameDirPath, Marker.GOG_SUPPORT_INSTALLED)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val parts = GOGService.getInstance()?.gogManager
            ?.getSupportCommandPartsForLaunch(appId) ?: return null
        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}
