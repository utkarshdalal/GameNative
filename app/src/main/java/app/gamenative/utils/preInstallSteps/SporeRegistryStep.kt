package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File

/**
 * Creates the registry entries normally supplied by Spore's Steam install script.
 * Without the SPORE key, Spore stops at startup with "Configuration script failed".
 */
object SporeRegistryStep : PreInstallStep {
    private const val SPORE_APP_ID = "17390"
    private const val REGISTRY_KEY = "HKLM\\Software\\Wow6432Node\\Electronic Arts\\SPORE"

    override val marker: Marker = Marker.SPORE_REGISTRY_INSTALLED

    private fun prefixStamp(container: Container): File =
        File(container.rootDir, ".wine/${Marker.SPORE_REGISTRY_INSTALLED.fileName}")

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean = gameSource == GameSource.STEAM

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        if (appId != SPORE_APP_ID ||
            (MarkerUtils.hasMarker(gameDirPath, marker) && prefixStamp(container).exists())
        ) {
            return null
        }

        val values = linkedMapOf(
            "InstallLoc" to "A:\\",
            "DataDir" to "A:\\Data",
            "AppDir" to "A:\\SporeBin",
        )
        val commands = values.map { (name, value) ->
            "reg add \"$REGISTRY_KEY\" /v $name /t REG_SZ /d \"$value\" /f"
        }

        try {
            prefixStamp(container).createNewFile()
        } catch (_: Exception) {
        }
        return commands.joinToString(" & ")
    }
}
