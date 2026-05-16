package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File

/**
 * Determines whether pre-install steps (VC Redist, GOG script interpreter) need to run
 * as Wine guest programs before the game launches. These installers require wine explorer
 * and cannot be run via execShellCommand.
 *
 * Each returned entry contains the marker and complete guest executable string for one
 * Wine session. The caller chains them via termination callbacks and persists markers
 * per-step as they complete.
 *
 * Completion is tracked via marker files in the game directory (not container config),
 * so importing a container config won't incorrectly skip pre-install steps.
 */
object PreInstallSteps {
    data class PreInstallCommand(
        val marker: Marker,
        val executable: String,
    )

    private val steps: List<PreInstallStep> = listOf(
        VcRedistStep,
        PhysXStep,
        OpenALStep,
        XnaFrameworkStep,
        GogScriptInterpreterStep,
        UbisoftConnectStep,
    )

    private var stepsProvider: () -> List<PreInstallStep> = { steps }
    private fun currentSteps(): List<PreInstallStep> = stepsProvider()
    private fun allMarkers(): List<Marker> = currentSteps().map { it.marker }.distinct()

    /**
     * Returns a list of pre-install commands (marker + guest executable). Each entry is a
     * separate Wine session. Returns empty list if nothing needs installing.
     */
    fun getPreInstallCommands(
        container: Container,
        appId: String,
        gameSource: GameSource,
        screenInfo: String,
        containerVariantChanged: Boolean,
    ): List<PreInstallCommand> {
        val gameDir = getGameDir(container) ?: return emptyList()
        val gameDirPath = gameDir.absolutePath

        if (containerVariantChanged) {
            resetMarkers(gameDirPath)
            container.rootDir?.absolutePath?.let { containerRoot ->
                resetMarkers(containerRoot)
                resetVcRedistVersionMarkers(containerRoot)
            }
        }

        val commands = mutableListOf<PreInstallCommand>()

        for (step in currentSteps()) {
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
                    commands.add(
                        PreInstallCommand(
                            marker = step.marker,
                            executable = wrapAsGuestExecutable(cmd, screenInfo),
                        ),
                    )
                }
            }
        }

        return commands
    }

    fun markAllDone(container: Container) {
        val gameDir = getGameDir(container) ?: return
        val gameDirPath = gameDir.absolutePath
        for (marker in allMarkers()) {
            MarkerUtils.addMarker(gameDirPath, marker)
        }
    }

    fun markStepDone(container: Container, marker: Marker) {
        val gameDir = getGameDir(container) ?: return
        val gameDirPath = gameDir.absolutePath
        MarkerUtils.addMarker(gameDirPath, marker)
        // Also persist container-scoped prereqs at the Wine prefix root so a
        // game reinstall doesn't force a redundant re-run of an installer that
        // already landed system-wide. For vcredist this is keyed per-year via
        // VcRedistStep.recordInstalledVersions so a later game bundling a
        // different MSVC year still triggers an install (just for the missing
        // years), instead of being short-circuited by a coarse container-wide
        // marker.
        if (marker == Marker.VCREDIST_INSTALLED) {
            VcRedistStep.recordInstalledVersions(container, gameDir)
        }
    }

    private fun resetMarkers(gameDirPath: String) {
        for (marker in allMarkers()) {
            MarkerUtils.removeMarker(gameDirPath, marker)
        }
    }

    /**
     * Clears per-year vcredist sidecar markers (".vcredist_installed_<year>")
     * at the container root. Called when the container variant changes so the
     * Wine prefix gets re-seeded with the right redistributables.
     */
    private fun resetVcRedistVersionMarkers(containerRoot: String) {
        val dir = java.io.File(containerRoot)
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isFile && f.name.startsWith(".vcredist_installed_")) {
                runCatching { f.delete() }
            }
        }
    }

    private fun wrapAsGuestExecutable(cmdChain: String, screenInfo: String): String {
        val wrapped = "winhandler.exe cmd /c \"$cmdChain & taskkill /F /IM explorer.exe & wineserver -k\""
        return "wine explorer /desktop=shell,$screenInfo $wrapped"
    }

    private fun getGameDir(container: Container): File? {
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[0].equals("A", ignoreCase = true)) return File(drive[1])
        }
        return null
    }

    /**
     * Test-only hook to override the pre-install step provider.
     * Not intended for production code paths.
     *
     * @param provider Steps provider for tests; pass null to restore the default provider.
     */
    internal fun setStepsProviderForTests(provider: (() -> List<PreInstallStep>)?) {
        stepsProvider = provider ?: { steps }
    }
}
