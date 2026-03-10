package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

object XnaFrameworkStep : PreInstallStep {
    override val marker: Marker = Marker.XNA_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return !MarkerUtils.hasMarker(gameDirPath, Marker.XNA_INSTALLED)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val searchDirs = listOf(
            File(gameDirPath, "_CommonRedist/xnafx"),
            File(gameDirPath, "redist"),
            File(gameDirPath, "Redist"),
        ).filter { it.exists() && it.isDirectory }

        if (searchDirs.isEmpty()) {
            Timber.tag("XnaFrameworkStep").i("No XNA Framework search directories found for game at $gameDirPath")
            return null
        }

        val parts = mutableListOf<String>()

        for (dir in searchDirs) {
            Timber.tag("XnaFrameworkStep").i("Searching for XNA installers under ${dir.absolutePath}")
            dir.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.name.startsWith("xna", ignoreCase = true) &&
                        file.name.endsWith(".msi", ignoreCase = true)
                }
                .forEach { msiFile ->
                    val relativePath = msiFile
                        .relativeTo(gameDir)
                        .path
                        .replace('/', '\\')
                    val winePath = "A:\\$relativePath"
                    Timber.tag("XnaFrameworkStep").i("Queued XNA installer: $winePath")

                    val command = "msiexec /i $winePath /quiet /norestart"
                    parts.add(command)
                }
        }

        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}

