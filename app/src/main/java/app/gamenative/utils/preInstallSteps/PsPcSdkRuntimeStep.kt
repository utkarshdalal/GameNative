package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Where a title keeps its PlayStation PC SDK runtime, found for both packages below.
 *
 * The batch script is the signal that a title needs the runtime at all — it is what Steam's
 * install script runs — and the two packages sit beside it. It is found by a bounded walk rather
 * than a hardcoded path: four levels, counted as `walkTopDown` counts (the game directory's own
 * children are depth 1), reaches both layouts in play — `<game>/SB/Binaries/Win64/` and an
 * engine's `<game>/<Project>/Binaries/Win64/` — and stops short of walking a whole install tree
 * for a file that is never there. Measured on an SD-card library (Stellar Blade, Metaphor, RE
 * requiem), a walk to this depth costs ~30 ms, the same within noise as one to five; a hardcoded
 * `SB/Binaries/Win64` was tried first and dropped, because it is one title's layout and it hides
 * a wrong assumption behind a working fast path.
 */
internal object PsPcSdkFiles {
    const val SCRIPT = "install_pspc_sdk_runtime.bat"
    const val MANAGER_MSI = "PsPcSdkRuntimeManager.msi"
    const val INSTALLER_MSI = "PsPcSdkRuntimeInstaller.msi"
    const val MAX_DEPTH = 4

    /** Where the runtime installs itself, relative to `ProgramData` in the prefix. */
    const val PREFIX_SDK_DIR = "Sony Interactive Entertainment Inc/PSPC_SDK"

    /** The directory holding the runtime, `null` when this title ships none. */
    fun findDir(gameDir: File): File? = runCatching {
        gameDir.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .firstOrNull { it.isFile && it.name.equals(SCRIPT, ignoreCase = true) }
            ?.parentFile
    }.getOrNull()

    /**
     * [file] as the guest sees it on the `A:` drive, or `null` when it cannot be passed as one
     * unquoted argument: the launcher splits the command line it is handed, and the wrapped form
     * steps reach it through cannot carry quotes.
     */
    fun guestPath(gameDir: File, file: File): String? {
        // `relativeTo` yields host separators, which are '/' on Android, so they are flipped
        // back for the `A:` drive.
        val relative = file.relativeTo(gameDir).path.replace('/', '\\')
        return "A:\\$relative".takeIf { !it.contains(' ') }
    }
}

/**
 * Installs the PlayStation PC SDK runtime a game ships beside its executable.
 *
 * Titles built against the PS PC SDK refuse to start until Sony's runtime is in the prefix —
 * Stellar Blade stops on "PSPC SDK runtime could not be found" before its first frame. The
 * runtime ships with the game, not with Steam: `install_pspc_sdk_runtime.bat` next to the
 * executable (Stellar Blade: `SB/Binaries/Win64/`), with two packages beside it. Steam runs it
 * from the game's `installscript.vdf`, and [SteamInstallScriptRegistry] marks every Run Process
 * step as already done so no redistributable is launched under Wine — which is why the runtime
 * is missing in the first place.
 *
 * The vendor script installs *two* packages, in this order:
 *
 *     msiexec /quiet /i PsPcSdkRuntimeManager.msi
 *     msiexec /quiet /i PsPcSdkRuntimeInstaller.msi
 *
 * so this is two steps rather than one, a package each. Calling `msiexec` directly instead of
 * the `.bat` that wraps it loses the vendor's script but buys two things: `msiexec` is a windowed
 * program, so the install runs with **no console window** ([PreInstallStep.viaShell]), and each
 * package gets its own session, ended by the framework's `wineserver -k` between steps instead of
 * two installs racing the single kill that follows a chained `cmd`.
 *
 * Completion is tracked twice, like the GOG support commands: the game-dir marker is cleared by
 * verify, and a stamp inside the prefix re-runs the package in a recreated prefix, whose registry
 * no longer has the runtime's keys.
 */
abstract class PsPcSdkPackageStep : PreInstallStep {
    /** The package this step installs, relative to the directory that holds the batch script. */
    protected abstract val msiName: String

    protected abstract val tag: String

    override val viaShell: Boolean = false

    /**
     * Marker alone. A recreated prefix re-runs this anyway: `PreInstallSteps.markersStale` sees
     * the prefix stamp missing and clears every game-dir marker before either entry point runs.
     * An extra per-step stamp would say the same thing twice — and, worse, [buildCommand] would
     * have to write it, which makes a *probe* (`hasPendingSteps`) mutate the prefix.
     */
    /**
     * What the package leaves in the prefix, when that is something this can check, or `null`
     * where it cannot be judged (a prefix that is not visible from here).
     */
    protected open fun installedInPrefix(container: Container): Boolean? = null

    /**
     * The marker, and — where the package leaves something to look at — the disk.
     *
     * A marker is written when the session ends, not when the install succeeds, so a failed
     * `msiexec` would otherwise be skipped on every later launch and the game would keep
     * starting without the runtime. The exit status cannot settle that either: the guest
     * executable is `wine explorer /desktop=…`, whose status is not the installer's. What the
     * game itself checks is cheaper and truer — if the runtime is not under `ProgramData`, it
     * is not installed, whatever the marker says.
     */
    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        if (!MarkerUtils.hasMarker(gameDirPath, marker)) return true
        return installedInPrefix(container) == false
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        // Nothing is logged on the way out of this function, including the paths that return
        // null: it runs for the `hasPendingSteps` probe as well as for the real chain, so any
        // line here appears once more than the install it describes. `PreInstallSteps` logs the
        // commands it schedules; a title that installs nothing simply has no such line.
        val runtimeDir = PsPcSdkFiles.findDir(gameDir) ?: return null
        val msi = File(runtimeDir, msiName)
        if (!msi.isFile) return null

        val guestPath = PsPcSdkFiles.guestPath(gameDir, msi)
        if (guestPath == null) {
            Timber.tag(tag).w("%s has a space in its path and cannot be passed unquoted", msi)
            return null
        }

        // `/quiet`: what the vendor script passes for both of its calls in quiet mode, and the
        // reason neither opens a UI.
        return "msiexec /i $guestPath /quiet"
    }
}

/** The runtime manager: the first of the two packages the vendor script installs. */
object PsPcSdkManagerStep : PsPcSdkPackageStep() {
    override val marker: Marker = Marker.PSPC_SDK_MANAGER_INSTALLED
    override val msiName: String = PsPcSdkFiles.MANAGER_MSI
    override val tag: String = "PsPcSdkManagerStep"
}

/** The runtime itself: the second package, and the one the game loads. */
object PsPcSdkRuntimeStep : PsPcSdkPackageStep() {
    override val marker: Marker = Marker.PSPC_SDK_RUNTIME_INSTALLED
    override val msiName: String = PsPcSdkFiles.INSTALLER_MSI
    override val tag: String = "PsPcSdkRuntimeStep"

    /**
     * The runtime's install root, the one the game looks for before it starts — from the log of a
     * working install: `C:\ProgramData\Sony Interactive Entertainment Inc\PSPC_SDK\S22\…`.
     */
    override fun installedInPrefix(container: Container): Boolean? {
        val programData = File(container.rootDir, ".wine/drive_c/ProgramData")
        if (!programData.isDirectory) return null
        return File(programData, PsPcSdkFiles.PREFIX_SDK_DIR).isDirectory
    }
}
