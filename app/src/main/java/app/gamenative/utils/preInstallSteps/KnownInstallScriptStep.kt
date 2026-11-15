package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.types.DepotManifest
import java.io.File
import timber.log.Timber

/** Runs the known steps of a game's Steam install scripts, and nothing else.
 *
 * Steam executes a game's install script Run Process steps when it installs the title. Nothing
 * here does: [SteamInstallScriptRegistry] only *marks* the steps it recognises as redistributables,
 * so Steam's own attempt is suppressed, and a vendor step that is not a redistributable is left
 * unmarked and never runs. Stellar Blade is the case that fails visibly — its
 * `install_pspc_sdk_runtime.bat` installs the PlayStation PC SDK runtime, and without it the game
 * stops on "PSPC SDK Runtime cannot start because it is missing from your computer".
 *
 * "Known" is [KNOWN_STEPS]: the program names, taken from the script's `process N` values, that this
 * app will execute. The list lives here rather than beside the parser, because adding a name is a
 * decision to execute a vendor-supplied file and belongs with the thing that executes it. The
 * arguments are **not** here — they are the step's own `command N` values, so
 * `install_pspc_sdk_runtime.bat` runs with the `quiet` its script specifies (quiet selects
 * `/quiet /i` for both of its `msiexec` calls and skips the trailing `pause`), and a script that
 * changes its arguments needs no change here.
 *
 * The scripts are the ones Steam itself would run, found the way Steam finds them: every depot
 * manifest flags its install scripts ([EDepotFileFlag.InstallScript]), wherever they sit in the
 * tree — Stellar Blade's is in `SB/Binaries/Win64/`, with no `installscript.vdf` at the install
 * root and no `installScript` in its app info. The engine keeps the installed manifests under the
 * game directory, so the lookup is a few small reads and never lists the game's files.
 *
 * The command is the batch file itself with those arguments, run **without the shell**
 * ([PreInstallStep.viaShell]): no `cmd /c` chain, so no console of ours and no
 * `taskkill`/`wineserver -k` on the end — the framework ends each install session itself.
 *
 * Completion is the game-dir marker, cleared by Verify Files and by `PreInstallSteps.markersStale`
 * when the prefix is recreated. A title with no known step gets the same marker the first time it
 * is asked, so the manifests are read once per install, not on every launch.
 */
object KnownInstallScriptStep : PreInstallStep {
    private const val TAG = "KnownInstallScriptStep"

    /**
     * The run-process steps this app may run, by the file name of the program they name.
     *
     * Keep it to programs installed with the game and named by the game's own install script.
     */
    private val KNOWN_STEPS: Set<String> = setOf(
        "install_pspc_sdk_runtime.bat",
    )

    /** What Steam looks for at the install root when an app's info carries no `installScript`. */
    private const val INSTALL_SCRIPT = "installscript.vdf"

    override val marker: Marker = Marker.KNOWN_INSTALL_SCRIPT_RUN

    override val viaShell: Boolean = false

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean = gameSource == GameSource.STEAM && !MarkerUtils.hasMarker(gameDirPath, marker)

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val scripts = installScripts(gameDir, appInfoScriptName(container))
        val step = knownSteps(scripts, gameDir).firstOrNull()
        if (step == null) {
            // Nothing to run is a settled answer, so it is marked like a run would be — the way
            // `GogSupportCommandsStep` marks a game whose manifest lists no support commands — and
            // the manifests are not parsed again until Verify Files or a recreated prefix clears
            // the marker. This is also the `hasPendingSteps` probe, and writing here is fine for
            // that: the marker claims no install, only that there is none to make, which stays
            // true whatever happens to the launch.
            if (MarkerUtils.addMarker(gameDirPath, marker)) explainAbsence(gameDir, scripts)
            return null
        }

        // One step at a time: `PreInstallSteps` runs each command in its own session, and a session
        // runs one program. A second known step would need its own `PreInstallStep`.
        //
        // Nothing is logged here, and nothing is written: this function is also the probe
        // `hasPendingSteps` runs before the session's server is chosen, so a log here reports a
        // launch twice and a write here happens twice. `PreInstallSteps` logs each command it
        // actually schedules, which is the line worth reading.
        val args = step.command.orEmpty()
        return if (args.isEmpty()) "\"${step.path}\"" else "\"${step.path}\" $args"
    }

    /**
     * The known steps the game's install scripts declare, whose program is present and whose
     * `Requirement_OS` this image satisfies, in the order the scripts give them.
     *
     * A redistributable's script is on the list too — Steam flags those the same way — and is
     * harmless here: none of its steps names a program on [KNOWN_STEPS].
     *
     * `Is64BitWindows 0` means the step is for a 32-bit Windows, which this image is not; Steam
     * skips it on a 64-bit system and so do we, rather than run an installer the game cannot load.
     */
    private fun knownSteps(
        scripts: List<File>,
        gameDir: File,
    ): List<SteamInstallScriptRegistry.RunProcessStep> = scripts.flatMap { script ->
        runCatching { SteamInstallScriptRegistry.parseRunProcessSteps(script.readText(), script, gameDir) }
            .onFailure { Timber.w(it, "Failed to read ${script.absolutePath}") }
            .getOrDefault(emptyList())
    }.filter { step ->
        val file = step.file ?: return@filter false
        file.isFile && file.name.lowercase() in KNOWN_STEPS && step.requires64BitWindows != false
    }

    private fun appInfoScriptName(container: Container): String? = runCatching {
        SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(container.id))?.installScript
    }.getOrNull()

    /**
     * The game's install scripts, as Steam would find them: every file the installed depot
     * manifests flag as an install script, plus the one app info names (`installscript.vdf` at the
     * install root when it names none), each resolved under [gameDir] and present on disk.
     *
     * The manifests are what Steam goes by, so a script Steam would run is on this list wherever
     * the depot put it, and a script Steam would not run — a stray copy somewhere in the tree — is
     * not. The app-info name covers an install the engine's manifest record does not describe.
     */
    internal fun installScripts(gameDir: File, scriptName: String?): List<File> {
        val named = scriptName?.trim().orEmpty().ifEmpty { INSTALL_SCRIPT }
        val paths = linkedSetOf<String>()
        paths += named
        paths += flaggedInstallScripts(gameDir)
        return paths.mapNotNull { resolveBelow(gameDir, it) }.distinctBy { it.absolutePath }
    }

    /** Paths, relative to the install root, that the installed depot manifests flag as install scripts. */
    private fun flaggedInstallScripts(gameDir: File): List<String> {
        val appDirPath = gameDir.absolutePath
        return DepotManifestFiles.installedManifests(appDirPath).flatMap { (depotId, gid) ->
            val file = DepotManifestFiles.manifestFile(appDirPath, depotId, gid.toLong())
            if (!file.isFile) return@flatMap emptyList()
            runCatching { DepotManifest.loadFromFile(file.absolutePath) }
                .onFailure { Timber.tag(TAG).w(it, "Could not read manifest ${file.name}") }
                .getOrNull()
                ?.files.orEmpty()
                .filter { EDepotFileFlag.InstallScript in it.flags }
                .map { it.fileName }
        }
    }

    /** [relative] resolved from [root] one component at a time, case-insensitively. */
    internal fun resolveBelow(root: File, relative: String): File? {
        var current = root
        for (segment in relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            if (segment == "." || segment == "..") return null
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current.takeIf { it.isFile }
    }

    /**
     * Why nothing is scheduled, in one line, when the marker is first written.
     *
     * Silence is the wrong default here: a title whose runtime install is skipped fails with the
     * same dialog as a title that never had one, so the difference has to be readable in a log.
     */
    private fun explainAbsence(gameDir: File, scripts: List<File>) {
        val why = if (scripts.isEmpty()) {
            "no install script at ${gameDir.absolutePath}: none flagged by its depot manifests, none at the root"
        } else {
            val wanted = KNOWN_STEPS.joinToString(" or ")
            "${scripts.joinToString { it.absolutePath }} name none of $wanted, or the program named is not on disk"
        }
        Timber.tag(TAG).i("nothing to run: %s", why)
    }
}
