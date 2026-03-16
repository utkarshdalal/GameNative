package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

object DirectXStep : PreInstallStep {
    override val marker: Marker = Marker.DIRECTX_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return container.containerVariant.equals(Container.GLIBC) &&
            !MarkerUtils.hasMarker(gameDirPath, Marker.DIRECTX_INSTALLED)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val searchDirs = listOf(
            File(gameDirPath, "_CommonRedist/DirectX"),
            File(gameDirPath, "DirectX"),
            File(gameDirPath, "directx"),
        ).filter { it.exists() && it.isDirectory }

        if (searchDirs.isEmpty()) {
            Timber.tag("DirectXStep").i("No DirectX search directories found for game at $gameDirPath")
            return null
        }

        val parts = mutableListOf<String>()

        for (dir in searchDirs) {
            Timber.tag("DirectXStep").i("Searching for DirectX installers under ${dir.absolutePath}")
            dir.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.name.equals("DXSETUP.exe", ignoreCase = true)
                }
                .forEach { installerFile ->
                    val relativePath = installerFile
                        .relativeTo(gameDir)
                        .path
                        .replace('/', '\\')
                    val winePath = "A:\\$relativePath"
                    Timber.tag("DirectXStep").i("Queued DirectX installer: $winePath")

                    // DirectX Jun 2010 and similar redistributables accept /silent
                    val command = "$winePath /silent"
                    parts.add(command)
                }
        }

        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}

