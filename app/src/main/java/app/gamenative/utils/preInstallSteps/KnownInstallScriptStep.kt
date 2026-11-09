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
        // Steam names the script when a game does not ship the conventional `installscript.vdf`.
        val scriptName = runCatching {
            SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(container.id))
                ?.installScript
        }.getOrNull()

        val step = knownSteps(gameDir, scriptName).firstOrNull()
        if (step == null) {
            // Once per process: `hasPendingSteps` probes this too, on every launch.
            if (explained.add(gameDirPath)) explainAbsence(gameDir, scriptName)
            return null
        }

        // One step at a time: `PreInstallSteps` runs each command in its own session, and a session
        // runs one program. A second known step would need its own `PreInstallStep`.
        //
        // Nothing is logged here, and nothing is written: this function is also the probe
        // `hasPendingSteps` runs before the session's server is chosen, so a log here reports a
        // launch twice and a write here happens twice. `PreInstallSteps` logs each command it
        // actually schedules, which is the line worth reading.
        val args = step.commands.joinToString(" ")
        return if (args.isEmpty()) "\"${step.path}\"" else "\"${step.path}\" $args"
    }

    /**
     * The known steps the game's own install script declares, whose program is present, in the order
     * the script gives them.
     *
     * Only the game's own script: a redistributable's script is Steam's business, and allowing one
     * to name a program would put every script under `_CommonRedist` on the launch path.
     */
    private fun knownSteps(
        gameDir: File,
        scriptName: String?,
    ): List<SteamInstallScriptRegistry.RunProcessStep> {
        val script = gameScript(gameDir, scriptName) ?: return emptyList()
        val steps = runCatching {
            SteamInstallScriptRegistry.parseRunProcessSteps(script.readText(), script, gameDir)
        }.onFailure { Timber.w(it, "Failed to read ${script.absolutePath}") }
            .getOrDefault(emptyList())
        return steps.filter { step ->
            val file = step.file ?: return@filter false
            file.isFile && file.name.lowercase() in KNOWN_STEPS
        }
    }

    private fun gameScript(gameDir: File, scriptName: String?): File? =
        findScriptBelow(gameDir, scriptName?.trim().orEmpty().ifEmpty { INSTALL_SCRIPT })

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
        var found: File? = null
        var foundDepth = Int.MAX_VALUE
        while (pending.isNotEmpty()) {
            val (dir, depth) = pending.removeFirst()
            if (depth > 0 && dir.name.equals(COMMON_REDIST, ignoreCase = true)) continue
            if (++dirs > SEARCH_DIRS) break
            val children = dir.listFiles()?.sortedBy { it.name } ?: continue
            if (depth < foundDepth) {
                for (child in children) {
                    if (!child.isDirectory && child.name.equals(name, ignoreCase = true)) {
                        found = child
                        foundDepth = depth
                        break
                    }
                }
            }
            if (depth == SEARCH_DEPTH) continue
            for (child in children) if (child.isDirectory) pending.add(child to depth + 1)
        }
        return found
    }

    /**
     * Why nothing is scheduled, in one line, once per game directory.
     *
     * Silence is the wrong default here: a title whose runtime install is skipped fails with the
     * same dialog as a title that never had one, so the difference has to be readable in a log.
     */
    private fun explainAbsence(gameDir: File, scriptName: String?) {
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
