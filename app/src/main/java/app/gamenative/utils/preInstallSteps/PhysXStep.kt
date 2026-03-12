package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

object PhysXStep : PreInstallStep {
    override val marker: Marker = Marker.PHYSX_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return !MarkerUtils.hasMarker(gameDirPath, Marker.PHYSX_INSTALLED)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val searchDirs = listOf(
            File(gameDirPath, "_CommonRedist/PhysX"),
            File(gameDirPath, "redist"),
            File(gameDirPath, "Redist"),
        ).filter { it.exists() && it.isDirectory }

        if (searchDirs.isEmpty()) {
            Timber.tag("PhysXStep").i("No PhysX search directories found for game at $gameDirPath")
            return null
        }

        val parts = mutableListOf<String>()

        for (dir in searchDirs) {
            Timber.tag("PhysXStep").i("Searching for PhysX installers under ${dir.absolutePath}")
            dir.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.name.startsWith("PhysX", ignoreCase = true) &&
                        (file.name.endsWith(".msi", ignoreCase = true) ||
                            file.name.endsWith(".exe", ignoreCase = true))
                }
                .forEach { installerFile ->
                    val relativePath = installerFile
                        .relativeTo(gameDir)
                        .path
                        .replace('/', '\\')
                    val winePath = "A:\\$relativePath"
                    Timber.tag("PhysXStep").i("Queued PhysX installer: $winePath")

                    val command =
                        if (installerFile.name.endsWith(".msi", ignoreCase = true)) {
                            "msiexec /i $winePath /quiet /norestart"
                        } else {
                            "$winePath /quiet /norestart"
                        }
                    parts.add(command)
                }
        }

        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}
