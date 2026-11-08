package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Installs the PlayStation PC SDK runtime a game ships beside its executable.
 *
 * Titles built against the PS PC SDK refuse to start until Sony's runtime is in the
 * prefix — Stellar Blade stops on "PSPC SDK runtime could not be found" before its
 * first frame. The runtime ships with the game, not with Steam: a
 * `install_pspc_sdk_runtime.bat` next to the executable (Stellar Blade:
 * `SB/Binaries/Win64/`) that installs the MSI sitting next to it. Steam runs it from
 * the game's `installscript.vdf`, and [SteamInstallScriptRegistry] marks every Run
 * Process step as already done so no redistributable is launched under Wine — which
 * is why the runtime is missing in the first place.
 *
 * The vendor script is run rather than the `.msi` beside it, and the shipped copy is
 * what says why: it installs **two** packages in order
 *
 *     msiexec /quiet /i PsPcSdkRuntimeManager.msi
 *     msiexec /quiet /i PsPcSdkRuntimeInstaller.msi
 *
 * so installing one MSI by hand would leave the runtime half there. Its first argument
 * is a quiet switch — `quiet` selects `/quiet /i` for both and skips the `pause` at the
 * end, which is the difference between a nine-minute session and a hung one — and it
 * `pushd`s to its own directory, so the command needs no `cd`.
 *
 * Completion is tracked twice, like the GOG support commands: the game-dir marker is
 * cleared by verify, and the prefix stamp re-runs the installer in a recreated prefix,
 * whose registry no longer has the runtime's keys.
 */
object PsPcSdkRuntimeStep : PreInstallStep {
    private const val TAG = "PsPcSdkRuntimeStep"
    private const val SCRIPT = "install_pspc_sdk_runtime.bat"

    /**
     * How deep to look for the script, counted from the game directory as Kotlin's
     * `walkTopDown` counts: the directory's own children are depth 1. Four reaches the
     * layouts this is for — `<game>/SB/Binaries/Win64/`, and an engine's
     * `<game>/<Project>/Binaries/Win64/` — and stops one level short of walking a whole
     * install tree for a file that is never there.
     *
     * Measured on the SD-card library (Stellar Blade, Metaphor, RE requiem): a walk to
     * this depth costs ~30 ms, the same within noise as one to five, so the bound is
     * about not descending rather than about microseconds.
     */
    private const val MAX_DEPTH = 4

    override val marker: Marker = Marker.PSPC_SDK_RUNTIME_INSTALLED

    private fun prefixStamp(container: Container): File =
        File(container.rootDir, ".wine/${marker.fileName}")

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean = !MarkerUtils.hasMarker(gameDirPath, marker) || !prefixStamp(container).exists()

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val script = findScript(gameDir)
        if (script == null) {
            Timber.tag(TAG).d("no %s within %d levels of %s", SCRIPT, MAX_DEPTH, gameDirPath)
            return null
        }

        // The guest sees a Windows path: `relativeTo` yields host separators, which
        // are '/' on Android, so they are flipped back for the `A:` drive.
        val guestPath = "A:\\" + script.relativeTo(gameDir).path.replace('/', '\\')
        // PreInstallSteps wraps this in `cmd /c "<command> & taskkill …"`, so the
        // command cannot carry quotes without breaking that line. A path with a space
        // is reported rather than mis-parsed into a half-run installer.
        if (guestPath.contains(' ')) {
            Timber.tag(TAG).w("%s path has a space and cannot be run unquoted: %s", SCRIPT, guestPath)
            return null
        }

        // The session marker is written by the framework on termination
        // (`PreInstallSteps.markStepDone`); stamp the prefix here with the same
        // optimistic semantics, so a recreated prefix re-runs the installer even
        // though the game-dir marker survived.
        runCatching { prefixStamp(container).parentFile?.mkdirs(); prefixStamp(container).createNewFile() }

        Timber.tag(TAG).i("running %s quiet", guestPath)
        // `quiet`: the script's own switch — `/quiet /i` for both MSIs, and no `pause`.
        // `< nul` stays as the fallback for a variant that ignores the argument and
        // reaches its `pause` anyway, which would otherwise hold the session open.
        return "$guestPath quiet < nul"
    }

    private fun findScript(gameDir: File): File? =
        runCatching {
            gameDir.walkTopDown()
                .maxDepth(MAX_DEPTH)
                .firstOrNull { it.isFile && it.name.equals(SCRIPT, ignoreCase = true) }
        }.getOrNull()
}
