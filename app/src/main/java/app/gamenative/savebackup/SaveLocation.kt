package app.gamenative.savebackup

import app.gamenative.enums.PathType

/**
 * A container-relative save location: a [PathType] root plus a relative subpath.
 *
 * The [relativeSubpath] is always normalized at construction so it is a pure relative path:
 * backslashes are converted to forward slashes, any absolute-path prefix (leading separators or a
 * Windows drive letter such as `C:`) is stripped, redundant separators are collapsed, and there is
 * never a leading or trailing path separator. An empty subpath denotes the [PathType] root itself.
 *
 * The save location never stores an absolute path (Requirement 1.1, 1.4). Resolution to an absolute
 * path is performed elsewhere by joining [PathType.toAbsPath] with [relativeSubpath].
 */
class SaveLocation(
    val pathType: PathType,
    relativeSubpath: String,
) {
    /** Normalized, relative, forward-slash subpath. Empty denotes the [PathType] root. */
    val relativeSubpath: String = normalizeSubpath(relativeSubpath)

    fun copy(
        pathType: PathType = this.pathType,
        relativeSubpath: String = this.relativeSubpath,
    ): SaveLocation = SaveLocation(pathType, relativeSubpath)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SaveLocation) return false
        return pathType == other.pathType && relativeSubpath == other.relativeSubpath
    }

    override fun hashCode(): Int = 31 * pathType.hashCode() + relativeSubpath.hashCode()

    override fun toString(): String =
        "SaveLocation(pathType=$pathType, relativeSubpath='$relativeSubpath')"

    companion object {
        /** The set of [PathType] values the save-backup engine handles explicitly. */
        val SUPPORTED_PATH_TYPES: Set<PathType> = setOf(
            PathType.WinMyDocuments,
            PathType.WinAppDataLocal,
            PathType.WinAppDataLocalLow,
            PathType.WinAppDataRoaming,
            PathType.WinSavedGames,
            PathType.WinProgramData,
            PathType.SteamUserData,
            PathType.Root,
        )

        /**
         * Normalize a raw subpath into a relative, forward-slash form with no leading/trailing
         * separator and no absolute-path prefix:
         * - convert `\` to `/`
         * - strip a Windows drive-letter prefix (e.g. `C:/foo` or `C:foo`)
         * - drop empty and `.` segments (removes leading/trailing/collapsed separators)
         * - resolve `..` segments within the subpath (e.g. `a/b/../c` → `a/c`)
         *
         * **Path-traversal safety.** A `..` that would escape above the subpath root (e.g. `../x`
         * or `a/../../x`) is invalid — the subpath must stay within its [PathType] root. Rather than
         * silently clamp such input (which could still map to an unintended location), this throws
         * [IllegalArgumentException]. Callers that build a [SaveLocation] from untrusted persisted
         * or discovered data must treat the failure as an unresolvable location. This closes the
         * traversal hole where a preserved `..` reached `Path.resolve` in the resolver.
         */
        fun normalizeSubpath(raw: String): String {
            // Convert Windows separators to '/'.
            var s = raw.replace('\\', '/')

            // Strip a Windows drive-letter prefix (e.g. "C:/foo" or "C:foo").
            if (s.length >= 2 && s[1] == ':' && s[0].isLetter()) {
                s = s.substring(2)
            }

            // Resolve segments with a stack: drop empty and '.'; pop on '..'; reject if '..' would
            // escape above the root.
            val stack = ArrayDeque<String>()
            for (segment in s.split('/')) {
                when {
                    segment.isEmpty() || segment == "." -> Unit
                    segment == ".." -> {
                        if (stack.isEmpty()) {
                            throw IllegalArgumentException(
                                "Save subpath escapes its root via '..': '$raw'",
                            )
                        }
                        stack.removeLast()
                    }
                    else -> stack.addLast(segment)
                }
            }
            return stack.joinToString("/")
        }
    }
}

/**
 * The outcome of resolving a persisted [SaveLocation] to an absolute filesystem path.
 */
sealed interface SaveLocationResult {
    /** The location resolved to an absolute path inside the container. */
    data class Resolved(
        val absolutePath: java.nio.file.Path,
        val saveLocation: SaveLocation,
    ) : SaveLocationResult

    /** No save location is persisted for the game (Requirement 1.6). */
    data object Unset : SaveLocationResult

    /**
     * A save location is persisted but cannot be resolved to an absolute path (Requirement 1.7),
     * e.g. its [PathType] is outside the supported set or a `SteamUserData` root has no resolvable
     * Steam account id. The persisted value is left unchanged.
     */
    data class Unresolved(val reason: String) : SaveLocationResult
}
