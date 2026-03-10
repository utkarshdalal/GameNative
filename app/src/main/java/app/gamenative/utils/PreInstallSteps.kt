package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import java.io.File

/**
 * Determines whether pre-install steps (VC Redist, GOG script interpreter) need to run
 * as Wine guest programs before the game launches. These installers require wine explorer
 * and cannot be run via execShellCommand.
 *
 * Each returned command is a complete guest executable string for one Wine session.
 * The caller chains them via termination callbacks, launching the game after the last one.
 *
 * Completion is tracked via marker files in the game directory (not container config),
 * so importing a container config won't incorrectly skip pre-install steps.
 */
object PreInstallSteps {
    private val steps: List<PreInstallStep> = listOf(
        VcRedistStep,
        GogScriptInterpreterStep,
    )

    private val allMarkers = steps.map { it.marker }.distinct()

    /**
     * Returns a list of guest executable commands for pre-install steps. Each entry is a
     * separate Wine session. Returns empty list if nothing needs installing.
     */
    fun getPreInstallCommands(
        container: Container,
        appId: String,
        gameSource: GameSource,
        screenInfo: String,
        containerVariantChanged: Boolean,
    ): List<String> {
        val gameDir = getGameDir(container) ?: return emptyList()
        val gameDirPath = gameDir.absolutePath

        if (containerVariantChanged) resetMarkers(gameDirPath)

        val commands = mutableListOf<String>()

        for (step in steps) {
            if (step.appliesTo(
                    container = container,
                    gameSource = gameSource,
                    gameDirPath = gameDirPath,
                )
            ) {
                step.buildCommand(
                    container = container,
                    appId = appId,
                    gameSource = gameSource,
                    gameDir = gameDir,
                    gameDirPath = gameDirPath,
                )?.let { cmd ->
                    commands.add(wrapAsGuestExecutable(cmd, screenInfo))
                }
            }
        }

        return commands
    }

    fun markAllDone(container: Container) {
        val gameDir = getGameDir(container) ?: return
        val gameDirPath = gameDir.absolutePath
        for (marker in allMarkers) {
            MarkerUtils.addMarker(gameDirPath, marker)
        }
    }

    private fun resetMarkers(gameDirPath: String) {
        for (marker in allMarkers) {
            MarkerUtils.removeMarker(gameDirPath, marker)
        }
    }

    private fun wrapAsGuestExecutable(cmdChain: String, screenInfo: String): String {
        val wrapped = "winhandler.exe cmd /c \"$cmdChain & taskkill /F /IM explorer.exe\""
        return "wine explorer /desktop=shell,$screenInfo $wrapped"
    }

    private fun getGameDir(container: Container): File? {
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[0].equals("A", ignoreCase = true)) return File(drive[1])
        }
        return null
    }
}
