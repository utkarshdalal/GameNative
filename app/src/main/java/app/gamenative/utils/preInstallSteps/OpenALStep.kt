package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

object OpenALStep : PreInstallStep {
    override val marker: Marker = Marker.OPENAL_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return !MarkerUtils.hasMarker(gameDirPath, Marker.OPENAL_INSTALLED)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val searchDirs = listOf(
            File(gameDirPath, "_CommonRedist/OpenAL"),
            File(gameDirPath, "redist"),
            File(gameDirPath, "Redist"),
        ).filter { it.exists() && it.isDirectory }

        if (searchDirs.isEmpty()) {
            Timber.tag("OpenALStep").i("No OpenAL search directories found for game at $gameDirPath")
            return null
        }

        val parts = mutableListOf<String>()

        for (dir in searchDirs) {
            Timber.tag("OpenALStep").i("Searching for OpenAL installers under ${dir.absolutePath}")
            dir.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        (file.name.equals("oalinst.exe", ignoreCase = true) ||
                            file.name.startsWith("OpenAL", ignoreCase = true)) &&
                        file.name.endsWith(".exe", ignoreCase = true)
                }
                .forEach { exeFile ->
                    val relativePath = exeFile
                        .relativeTo(gameDir)
                        .path
                        .replace('/', '\\')
                    val winePath = "A:\\$relativePath"
                    Timber.tag("OpenALStep").i("Queued OpenAL installer: $winePath")

                    val command = "$winePath /s"
                    parts.add(command)
                }
        }

        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}

