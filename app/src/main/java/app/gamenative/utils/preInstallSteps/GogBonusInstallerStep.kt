package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.gog.GOGBonusDlcInstaller
import com.winlator.container.Container
import java.io.File

/**
 * Runs GOG DLC offline installers staged by [GOGBonusDlcInstaller] silently before launch. Applies
 * on every launch while a pending_installer.txt exists; the command deletes it on exit code 0,
 * so the game-dir marker the framework writes afterwards does not gate this step.
 */
object GogBonusInstallerStep : PreInstallStep {
    override val marker: Marker = Marker.GOG_BONUS_INSTALLER_RUN

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return gameSource == GameSource.GOG &&
            GOGBonusDlcInstaller.pendingInstallerMarkers(File(gameDirPath)).isNotEmpty()
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val parts = GOGBonusDlcInstaller.pendingInstallerMarkers(gameDir)
            .mapNotNull { GOGBonusDlcInstaller.installerCommand(gameDir, it) }
        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}
