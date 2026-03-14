package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import java.io.File

object GogScriptInterpreterStep : PreInstallStep {
    override val marker: Marker = Marker.GOG_SCRIPT_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return gameSource == GameSource.GOG &&
            container.containerVariant.equals(Container.GLIBC) &&
            !MarkerUtils.hasMarker(gameDirPath, Marker.GOG_SCRIPT_INSTALLED)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val parts = GOGService.getInstance()?.gogManager
            ?.getScriptInterpreterPartsForLaunch(appId) ?: return null
        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}
