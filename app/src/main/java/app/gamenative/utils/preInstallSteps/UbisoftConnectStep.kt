package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

object UbisoftConnectStep : PreInstallStep {
    override val marker: Marker = Marker.UBISOFT_CONNECT_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        if (MarkerUtils.hasMarker(gameDirPath, Marker.UBISOFT_CONNECT_INSTALLED)) return false
        if (!container.getExtra("installUbisoftConnect", "false").toBoolean()) return false

        return true
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        // The Ubisoft Connect installer is downloaded ahead of time by a LaunchDependency
        // into _CommonRedist/UbisoftConnect under the game directory. We just invoke it.
        val installerHostPath = File(gameDir, "_CommonRedist/UbisoftConnect/UbisoftConnectInstaller.exe")
        if (!installerHostPath.isFile) {
            Timber.tag("UbisoftConnectStep").i(
                "Ubisoft Connect installer not present at expected path for game at %s",
                gameDirPath,
            )
            return null
        }

        val winePath = "A:\\_CommonRedist\\UbisoftConnect\\UbisoftConnectInstaller.exe"
        val command = "$winePath /S"
        Timber.tag("UbisoftConnectStep").i("Using Ubisoft Connect installer (silent): %s", command)

        return command
    }
}

