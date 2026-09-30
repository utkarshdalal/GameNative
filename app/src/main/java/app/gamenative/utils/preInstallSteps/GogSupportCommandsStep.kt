package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.gog.GOGManifestUtils
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import java.io.File

/**
 * Runs Gen 1 (legacy) GOG support_commands: per-game setup executables shipped in the
 * support depot that create registry keys, shortcuts, etc. Galaxy and Heroic run these
 * after install; without them many old titles fail (e.g. CD checks from missing keys).
 *
 * Completion is tracked twice: the game-dir marker (framework convention, cleared by
 * verify) and a stamp inside the container's Wine prefix. The prefix stamp makes the
 * installers rerun when the same install is launched in a newly created prefix, whose
 * registry no longer has the keys the first run created.
 */
object GogSupportCommandsStep : PreInstallStep {
    override val marker: Marker = Marker.GOG_SUPPORT_INSTALLED

    private fun prefixStamp(container: Container): File =
        File(container.rootDir, ".wine/${Marker.GOG_SUPPORT_INSTALLED.fileName}")

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return gameSource == GameSource.GOG &&
            (
                !MarkerUtils.hasMarker(gameDirPath, Marker.GOG_SUPPORT_INSTALLED) ||
                    !prefixStamp(container).exists()
                )
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val manifest = GOGManifestUtils.readLocalManifest(gameDir) ?: return null
        val commands = manifest.optJSONArray("supportCommands")
        if (commands == null || commands.length() == 0) {
            markDone(container, gameDirPath)
            return null
        }

        val parts = GOGService.getInstance()?.gogManager
            ?.getSupportCommandPartsForLaunch(appId, gameDir) ?: return null
        if (parts.isEmpty()) {
            markDone(container, gameDirPath)
            return null
        }

        // The session marker is written by the framework on termination; stamp the
        // prefix here with the same optimistic semantics.
        try {
            prefixStamp(container).createNewFile()
        } catch (_: Exception) {
        }
        return parts.joinToString(" & ")
    }

    private fun markDone(container: Container, gameDirPath: String) {
        MarkerUtils.addMarker(gameDirPath, Marker.GOG_SUPPORT_INSTALLED)
        try {
            prefixStamp(container).createNewFile()
        } catch (_: Exception) {
        }
    }
}
