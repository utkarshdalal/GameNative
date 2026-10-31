package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import timber.log.Timber

object UbisoftConnectStep : PreInstallStep {
    private const val TAG = "UbisoftConnectStep"
    private val INSTALLER_NAMES = listOf("UbisoftConnectInstaller.exe", "UplayInstaller.exe")
    private const val COMMON_REDIST_SUBDIR = "_CommonRedist/UbisoftConnect"
    private const val INSTALLED_EXE_PATH =
        ".wine/drive_c/Program Files (x86)/Ubisoft/Ubisoft Game Launcher/UbisoftConnect.exe"

    override val marker: Marker = Marker.UBISOFT_CONNECT_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        if (MarkerUtils.hasMarker(gameDirPath, Marker.UBISOFT_CONNECT_INSTALLED)) {
            val rootDir = container.rootDir ?: return false
            if (File(rootDir, INSTALLED_EXE_PATH).isFile) return false

            Timber.tag(TAG).w(
                "Ubisoft install marker exists but UbisoftConnect.exe is missing; retrying installer",
            )
            MarkerUtils.removeMarker(gameDirPath, Marker.UBISOFT_CONNECT_INSTALLED)
        }

        return true
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val commonRedistDir = File(gameDir, COMMON_REDIST_SUBDIR)
        val installerName =
            INSTALLER_NAMES.firstOrNull { installer ->
                ensureInstallerAtCommonRedist(
                    rootInstaller = File(gameDir, installer),
                    commonRedistDir = commonRedistDir,
                    commonRedistInstaller = File(commonRedistDir, installer),
                )
            }

        if (installerName == null) {
            Timber.tag(TAG).i(
                "Ubisoft installer not present at expected _CommonRedist path for game at %s",
                gameDirPath,
            )
            return null
        }

        val wineInstallerPath = "A:\\_CommonRedist\\UbisoftConnect\\$installerName"
        val command = "$wineInstallerPath /S"
        Timber.tag(TAG).i("Using Ubisoft installer (silent): %s", command)

        return command
    }

    /**
     * IMPORTANT: Ubisoft Connect installer cannot be run from the game root directory.
     * Ensures the Ubisoft Connect installer is present at the expected _CommonRedist path.
     * If it's only present in the game root, creates a symlink there first.
     */
    private fun ensureInstallerAtCommonRedist(
        rootInstaller: File,
        commonRedistDir: File,
        commonRedistInstaller: File,
    ): Boolean {
        if (commonRedistInstaller.isFile) return true
        if (!rootInstaller.isFile) return false

        try {
            commonRedistDir.mkdirs()
            Files.createSymbolicLink(commonRedistInstaller.toPath(), rootInstaller.toPath())
            return commonRedistInstaller.exists()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed creating Ubisoft symlink")
            return false
        }
    }
}
