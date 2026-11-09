package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/** Runs the known steps of a game's Steam install script, and nothing else.
 *
 * Steam executes a game's `installscript.vdf` Run Process steps when it installs the title. Nothing
 * here does: [SteamInstallScriptRegistry] only *marks* the steps it recognises as redistributables,
 * so Steam's own attempt is suppressed, and a vendor step that is not a redistributable is left
 * unmarked and never runs. Stellar Blade is the case that fails visibly — its
 * `install_pspc_sdk_runtime.bat` installs the PlayStation PC SDK runtime, and without it the game
 * stops on "PSPC SDK Runtime cannot start because it is missing from your computer".
 *
 * "Known" is [KNOWN_STEPS]: the program names, taken from the script's `process N` values, that this
 * app will execute. The list lives here rather than beside the parser, because adding a name is a
 * decision to execute a vendor-supplied file and belongs with the thing that executes it. The arguments are **not** here — they are the step's own `command N` values, so
 * `install_pspc_sdk_runtime.bat` runs with the `quiet` its script specifies (quiet selects
 * `/quiet /i` for both of its `msiexec` calls and skips the trailing `pause`), and a script that
 * changes its arguments needs no change here.
 *
 * The command is the batch file itself with those arguments, run **without the shell**
 * ([PreInstallStep.viaShell]): no `cmd /c` chain, so no console of ours and no
 * `taskkill`/`wineserver -k` on the end — the framework ends each install session itself.
 *
 * Completion is the game-dir marker, cleared by Verify Files and by `PreInstallSteps.markersStale`
 * when the prefix is recreated.
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

    /** What Steam looks for when an app's info carries no `installScript` of its own. */
    private const val INSTALL_SCRIPT = "installscript.vdf"

    /** Steam's redistributable folder: its scripts are read separately and are never the game's. */
    private const val COMMON_REDIST = "_CommonRedist"

    /** How deep below the install root a script is looked for. */
    private const val SEARCH_DEPTH = 4

    /** How many directories that search may visit before giving up. */
    private const val SEARCH_DIRS = 512

    /** Game directories already reported as having nothing to run. */
    private val explained = ConcurrentHashMap.newKeySet<String>()

    /**
     * What each game directory yields, by game directory.
     *
     * `hasPendingSteps` runs this from composition, on every launch, and a title without a script
     * pays the whole search each time. The script is a file in the game's own install: within one
     * process it cannot appear or change, so one answer per directory is enough.
     */
    private val stepsByDir = ConcurrentHashMap<String, List<SteamInstallScriptRegistry.RunProcessStep>>()

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
        val step = knownSteps(container, gameDir, gameDirPath).firstOrNull()
        if (step == null) {
            // Once per process: `hasPendingSteps` probes this too, on every launch.
            if (explained.add(gameDirPath)) explainAbsence(container, gameDir)
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
     * The known steps the game's own install script declares, whose program is present and whose
     * `Requirement_OS` this image satisfies, in the order the script gives them.
     *
     * Only the game's own script: a redistributable's script is Steam's business, and allowing one
     * to name a program would put every script under `_CommonRedist` on the launch path.
     *
     * `Is64BitWindows 0` means the step is for a 32-bit Windows, which this image is not; Steam
     * skips it on a 64-bit system and so do we, rather than run an installer the game cannot load.
     */
    private fun knownSteps(
        container: Container,
        gameDir: File,
        gameDirPath: String,
    ): List<SteamInstallScriptRegistry.RunProcessStep> = stepsByDir.getOrPut(gameDirPath) {
        val scriptName = runCatching {
            SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(container.id))
                ?.installScript
        }.getOrNull()
        val script = gameScript(gameDir, scriptName) ?: return@getOrPut emptyList()
        val steps = runCatching {
            SteamInstallScriptRegistry.parseRunProcessSteps(script.readText(), script, gameDir)
        }.onFailure { Timber.w(it, "Failed to read ${script.absolutePath}") }
            .getOrDefault(emptyList())
        steps.filter { step ->
            val file = step.file ?: return@filter false
            file.isFile && file.name.lowercase() in KNOWN_STEPS && step.requires64BitWindows != false
        }
    }

    /**
     * The game's install script: the name Steam's app info gives, at the install root or below it.
     *
     * Steam's value is usually a bare file name, but it may be a path relative to the install root,
     * and a path compared against child names matches nothing. Resolve one component at a time,
     * case-insensitively, the way `SteamInstallScriptRegistry` resolves a step's own path.
     */
    private fun gameScript(gameDir: File, scriptName: String?): File? {
        val name = scriptName?.trim().orEmpty().ifEmpty { INSTALL_SCRIPT }
        return if (name.contains('/') || name.contains('\\')) resolveBelow(gameDir, name)
        else findScriptBelow(gameDir, name)
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
     * The shallowest `name` at or below [gameDir]'s fourth level, or `null`.
     *
     * A depot can put the script beside the game's shipping executable instead of at the install
     * root — Stellar Blade ships it in `SB/Binaries/Win64/`, next to its exes and its two
     * installers — and Steam resolves it against the game's launch folder, which is why that layout
     * works there.
     *
     * Two rules make the answer the game's own script rather than a bystander's. `_CommonRedist` is
     * skipped: it holds one `installscript.vdf` per redistributable, and on Stellar Blade it is also
     * the first directory in listing order, so a plain depth-first walk finds
     * `_CommonRedist/DirectX/Jun2010/installscript.vdf` and stops on a script whose steps are DXSETUP
     * and vcredist. And the walk is breadth-first, so the shallowest match wins instead of whichever
     * one a depth-first order happens to reach.
     *
     * The bound is on directories visited as well as on depth: a title that ships no script pays one
     * shallow listing, and a title with tens of thousands of loose files cannot turn this into a
     * full walk.
     */
    private fun findScriptBelow(gameDir: File, name: String): File? {
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(gameDir to 0)
        var dirs = 0
        while (pending.isNotEmpty()) {
            val (dir, depth) = pending.removeFirst()
            if (depth > 0 && dir.name.equals(COMMON_REDIST, ignoreCase = true)) continue
            if (++dirs > SEARCH_DIRS) return null
            val children = dir.listFiles()?.sortedBy { it.name } ?: continue
            // Breadth-first, so the first match found is at the shallowest depth; nothing later
            // can beat it and the walk can stop here.
            for (child in children) {
                if (!child.isDirectory && child.name.equals(name, ignoreCase = true)) return child
            }
            if (depth == SEARCH_DEPTH) continue
            for (child in children) if (child.isDirectory) pending.add(child to depth + 1)
        }
        return null
    }

    /**
     * Why nothing is scheduled, in one line, once per game directory.
     *
     * Silence is the wrong default here: a title whose runtime install is skipped fails with the
     * same dialog as a title that never had one, so the difference has to be readable in a log.
     */
    private fun explainAbsence(container: Container, gameDir: File) {
        val scriptName = runCatching {
            SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(container.id))
                ?.installScript
        }.getOrNull()
        val script = gameScript(gameDir, scriptName)
        val why = if (script == null) {
            "no install script at ${gameDir.absolutePath} or within its first levels"
        } else {
            // No second parse: this runs on the launch path, and [KNOWN_STEPS] is a short list.
            val wanted = KNOWN_STEPS.joinToString(" or ")
            "${script.absolutePath} names none of $wanted, or the program it names is not on disk"
        }
        Timber.tag(TAG).i("nothing to run: %s", why)
    }
}
