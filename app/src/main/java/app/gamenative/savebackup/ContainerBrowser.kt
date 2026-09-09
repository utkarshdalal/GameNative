package app.gamenative.savebackup

import app.gamenative.enums.PathType
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * In-app directory browser over a container's `Wine_Drive_C` filesystem (Requirement 3).
 *
 * Android's system document picker cannot browse the app-internal container storage, so this
 * component provides the navigation logic (list / into / up) the container-side picker UI is built
 * on top of. It is deliberately a pure, Android-UI-free, `java.nio.file`-based component so it can
 * be driven directly with temporary directories in tests (Task 8.4). The Compose UI (Task 8.5) and
 * the confirm → [SaveLocation] mapping (Task 8.2) are layered on top of it.
 *
 * The browser is opened with [open], which establishes the initial view at `drive_c`
 * (Requirement 3.1) or reports the container filesystem as unavailable (Requirement 3.2). From an
 * open [BrowserView] the caller navigates with [BrowserView.into], [BrowserView.up], and
 * [BrowserView.list], and never navigates above the `drive_c` root (Requirements 3.4–3.7).
 */
class ContainerBrowser private constructor(
    /** Absolute path of the `Wine_Drive_C` root this browser is clamped to. */
    private val driveC: Path,
    /**
     * Supported [PathType] → absolute root path within this container, used by the confirm mapping
     * (Task 8.2). Empty when the browser was built directly over a `drive_c` path for navigation
     * tests ([forDriveC]); confirm then rejects everything since no roots are known.
     */
    private val candidateRoots: Map<PathType, Path> = emptyMap(),
) {

    /**
     * Establish the initial view at `drive_c`.
     *
     * @return [OpenResult.Available] with the initial [BrowserView] rooted at `drive_c`
     *   (Requirement 3.1), or [OpenResult.Unavailable] if `drive_c` is missing or unreadable
     *   (Requirement 3.2).
     */
    fun open(): OpenResult {
        if (!isReadableDirectory(driveC)) {
            return OpenResult.Unavailable(CONTAINER_UNAVAILABLE)
        }
        return OpenResult.Available(BrowserView(currentDir = driveC))
    }

    /** Whether [path] exists, is a directory, and can be listed. */
    private fun isReadableDirectory(path: Path): Boolean =
        Files.isDirectory(path) && Files.isReadable(path)

    /**
     * List the immediate entries of [dir] for display, directories first then files, each sorted
     * case-insensitively by name.
     *
     * @return the entries, or `null` if [dir] cannot be read.
     */
    private fun listEntries(dir: Path): List<BrowserEntry>? {
        if (!isReadableDirectory(dir)) return null
        return try {
            Files.list(dir).use { stream ->
                stream
                    .map { path ->
                        BrowserEntry(
                            name = path.fileName.toString(),
                            path = path,
                            isDirectory = Files.isDirectory(path),
                        )
                    }
                    .toList()
            }
                .sortedWith(
                    compareByDescending<BrowserEntry> { it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compute the subset of the seven common Windows save roots that actually exist within
     * `drive_c`, exposed as navigable shortcut entries (Requirement 3.3).
     *
     * The absolute location of each root is derived the same way [PathType.toAbsPath] derives it,
     * so the shortcuts always point at the exact directories the resolver would resolve to:
     * `Documents`, `AppData/Local`, `AppData/LocalLow`, `AppData/Roaming`, and `Saved Games` live
     * under `users/<user>/`, while `ProgramData` and `Program Files` live at the `drive_c` root.
     * Only roots that currently exist as directories are surfaced.
     */
    private fun existingSaveRoots(): List<SaveRootShortcut> {
        val user = ImageFs.USER
        val usersHome = driveC.resolve("users").resolve(user)
        val candidates = listOf(
            SaveRootShortcut("Documents", usersHome.resolve("Documents")),
            SaveRootShortcut("AppData/Local", usersHome.resolve("AppData").resolve("Local")),
            SaveRootShortcut("AppData/LocalLow", usersHome.resolve("AppData").resolve("LocalLow")),
            SaveRootShortcut("AppData/Roaming", usersHome.resolve("AppData").resolve("Roaming")),
            SaveRootShortcut("Saved Games", usersHome.resolve("Saved Games")),
            SaveRootShortcut("ProgramData", driveC.resolve("ProgramData")),
            SaveRootShortcut("Program Files", driveC.resolve("Program Files")),
        )
        return candidates.filter { isReadableDirectory(it.path) }
    }

    /** A single view over one directory of the `drive_c` tree. */
    inner class BrowserView internal constructor(
        /** The directory currently displayed. Always at or below the `drive_c` root. */
        val currentDir: Path,
    ) {
        /**
         * Whether upward navigation is allowed from this view. `false` at the `drive_c` root so
         * navigation can never go above it (Requirement 3.7).
         */
        val canGoUp: Boolean get() = currentDir != driveC

        /** The entries of [currentDir] (directories first), or an empty list if unreadable. */
        fun list(): List<BrowserEntry> = listEntries(currentDir) ?: emptyList()

        /**
         * The subset of the seven common Windows save roots that exist within `drive_c`, exposed as
         * navigable shortcuts (Requirement 3.3). Independent of the current view so the picker UI
         * can always offer them for orientation.
         */
        fun saveRootShortcuts(): List<SaveRootShortcut> = existingSaveRoots()

        /**
         * Navigate into [dir], a directory expected to be an immediate child of [currentDir].
         *
         * @return [NavResult.Ok] with a view of [dir] on success (Requirement 3.4), or
         *   [NavResult.Error] leaving the current view unchanged if [dir] is not a readable
         *   subdirectory of the current view (Requirement 3.5).
         */
        fun into(dir: Path): NavResult {
            val target = dir.normalize()
            // Guard: the target must be a child within the current view and stay under drive_c.
            if (!target.startsWith(currentDir) || target == currentDir || !target.startsWith(driveC)) {
                return NavResult.Error(this, "$INTO_ERROR_PREFIX ${dir.fileName}")
            }
            if (!isReadableDirectory(target)) {
                return NavResult.Error(this, "$INTO_ERROR_PREFIX ${dir.fileName}")
            }
            return NavResult.Ok(BrowserView(target))
        }

        /**
         * Navigate to the parent directory (Requirement 3.6). Disabled at the `drive_c` root so
         * navigation never goes above it (Requirement 3.7).
         *
         * @return [NavResult.Ok] with the parent view, or [NavResult.Error] (view unchanged) when
         *   already at the `drive_c` root.
         */
        fun up(): NavResult {
            if (!canGoUp) {
                return NavResult.Error(this, UP_AT_ROOT_ERROR)
            }
            val parent = currentDir.parent
            // Defensive clamp: never surface a parent above drive_c.
            if (parent == null || !parent.startsWith(driveC)) {
                return NavResult.Error(this, UP_AT_ROOT_ERROR)
            }
            return NavResult.Ok(BrowserView(parent))
        }

        /**
         * Whether [currentDir] can be confirmed — i.e. it maps to a supported [PathType] root
         * (Requirement 3.9; Design "listable ≠ selectable"). The picker UI disables/explains the
         * "Select this folder" affordance when this is `false`, so a user does not navigate deep
         * into a listable-but-not-selectable location (e.g. `Program Files`) only to be refused.
         */
        val isConfirmable: Boolean get() = ContainerBrowserConfirm.isConfirmable(candidateRoots, currentDir)

        /**
         * Confirm the current directory (Requirement 3.8, 3.9), mapping it to the longest (most
         * specific) matching supported [PathType] root. Persistence is performed separately by
         * [ContainerBrowserConfirm.confirmAndPersist] so this pure mapping stays testable.
         */
        fun confirm(): ConfirmResult = ContainerBrowserConfirm.map(candidateRoots, currentDir)

        /** The supported [PathType] → absolute root map backing confirm, for the persist wrapper. */
        fun confirmCandidateRoots(): Map<PathType, Path> = candidateRoots
    }

    companion object {
        const val CONTAINER_UNAVAILABLE = "container filesystem unavailable"
        const val INTO_ERROR_PREFIX = "directory is unreadable:"
        const val UP_AT_ROOT_ERROR = "already at the drive_c root"

        /**
         * Open a browser for [container], deriving `drive_c` the same way [PathType] does
         * (`<container root>/.wine/drive_c`). [appId]/[accountId] are used to derive the supported
         * [PathType] roots the confirm mapping (Task 8.2) needs.
         */
        fun open(container: Container, appId: Int, accountId: Long): OpenResult =
            forContainer(container, appId, accountId).open()

        /** Build (but do not open) a browser for [container] with confirm support. */
        fun forContainer(container: Container, appId: Int, accountId: Long): ContainerBrowser =
            ContainerBrowser(
                driveC = driveCOf(container),
                candidateRoots = ContainerBrowserConfirm.candidateRoots(container, appId, accountId),
            )

        /**
         * Build a browser directly over a [driveC] path with an explicit [candidateRoots] map.
         * Exposed for tests so the navigation and confirm logic can be driven with temporary
         * directories without constructing a real [Container].
         */
        fun forDriveC(
            driveC: Path,
            candidateRoots: Map<PathType, Path> = emptyMap(),
        ): ContainerBrowser = ContainerBrowser(driveC.normalize(), candidateRoots)

        /** Derive the `Wine_Drive_C` path for [container]: `<container root>/.wine/drive_c`. */
        fun driveCOf(container: Container): Path =
            Paths.get(container.rootDir.absolutePath, ".wine", "drive_c").normalize()
    }
}

/** A single directory or file entry displayed in the browser. */
data class BrowserEntry(
    val name: String,
    val path: Path,
    val isDirectory: Boolean,
)

/** A navigable shortcut to a common Windows save root that exists within `drive_c`. */
data class SaveRootShortcut(
    /** Human-readable label, e.g. `AppData/Local`. */
    val label: String,
    /** Absolute path of the root within `drive_c`. */
    val path: Path,
)

/** The outcome of [ContainerBrowser.open]. */
sealed interface OpenResult {
    /** `drive_c` exists and is readable; [view] is the initial view (Requirement 3.1). */
    data class Available(val view: ContainerBrowser.BrowserView) : OpenResult

    /** `drive_c` is missing or unreadable; no location can be returned (Requirement 3.2). */
    data class Unavailable(val reason: String) : OpenResult
}

/** The outcome of a navigation step ([ContainerBrowser.BrowserView.into] / `up`). */
sealed interface NavResult {
    /** Navigation succeeded; [view] is the new current view. */
    data class Ok(val view: ContainerBrowser.BrowserView) : NavResult

    /**
     * Navigation failed. [view] is the unchanged current view (Requirement 3.5, 3.7) and [reason]
     * describes the error for the UI to surface.
     */
    data class Error(val view: ContainerBrowser.BrowserView, val reason: String) : NavResult
}
