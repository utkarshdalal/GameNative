package app.gamenative.savebackup

import app.gamenative.data.SaveFilePattern
import app.gamenative.enums.PathType

/**
 * A single container-relative save root: a [PathType] root plus a relative subpath, optionally
 * carrying the UFS [pattern] that selects which files under the root are saves.
 *
 * The [relativeSubpath] is always normalized at construction so it is a pure relative path:
 * backslashes are converted to forward slashes, any absolute-path prefix (leading separators or a
 * Windows drive letter such as `C:`) is stripped, redundant separators are collapsed, and there is
 * never a leading or trailing path separator. An empty subpath denotes the [PathType] root itself.
 *
 * A save root never stores an absolute path (Requirement 1.1, 1.4). Resolution to an absolute path
 * is performed elsewhere by joining [PathType.toAbsPath] with [relativeSubpath].
 *
 * When [pattern] is non-null, export filters the files under this root to those matched by the
 * pattern's glob and recursion depth (Requirement 2.7). When [pattern] is null (e.g. a
 * browser-confirmed root — Requirement 3.8), the whole subtree under the root is treated as saves.
 */
class SaveRoot(
    val pathType: PathType,
    relativeSubpath: String,
    val pattern: SaveFilePattern? = null,
) {
    /** Normalized, relative, forward-slash subpath. Empty denotes the [PathType] root. */
    val relativeSubpath: String = normalizeSubpath(relativeSubpath)

    fun copy(
        pathType: PathType = this.pathType,
        relativeSubpath: String = this.relativeSubpath,
        pattern: SaveFilePattern? = this.pattern,
    ): SaveRoot = SaveRoot(pathType, relativeSubpath, pattern)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SaveRoot) return false
        return pathType == other.pathType &&
            relativeSubpath == other.relativeSubpath &&
            pattern == other.pattern
    }

    override fun hashCode(): Int {
        var result = pathType.hashCode()
        result = 31 * result + relativeSubpath.hashCode()
        result = 31 * result + (pattern?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SaveRoot(pathType=$pathType, relativeSubpath='$relativeSubpath', pattern=$pattern)"

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
            // Req 14: the retired engine backed up install-dir saves (its discovery filtered on
            // isWindows, which includes GameInstall). Including it both preserves that behaviour
            // and prevents a GameInstall root being discovered, persisted, then rejected forever.
            PathType.GameInstall,
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
         * [IllegalArgumentException]. Callers that build a [SaveRoot] from untrusted persisted
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
 * A game's complete save location: an ordered, non-empty set of [SaveRoot]s (Requirement 1.1a).
 *
 * A game's saves may span several container roots — e.g. a Steam game with two UFS roots plus
 * `SteamUserData` — so a [SaveLocation] is a set, not a single root. Order is the discovery or
 * confirm order and is preserved. The set is never empty.
 */
class SaveLocation private constructor(
    val roots: List<SaveRoot>,
) {
    init {
        require(roots.isNotEmpty()) { "A SaveLocation must contain at least one SaveRoot" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SaveLocation) return false
        return roots == other.roots
    }

    override fun hashCode(): Int = roots.hashCode()

    override fun toString(): String = "SaveLocation(roots=$roots)"

    companion object {
        /** A [SaveLocation] from an ordered, non-empty list of [SaveRoot]s. */
        fun of(roots: List<SaveRoot>): SaveLocation = SaveLocation(roots)

        /** A single-root [SaveLocation] (e.g. a browser-confirmed location — Requirement 3.8). */
        fun of(root: SaveRoot): SaveLocation = SaveLocation(listOf(root))

        /** A single-root [SaveLocation] from a [PathType] + subpath. */
        fun single(pathType: PathType, relativeSubpath: String): SaveLocation =
            SaveLocation(listOf(SaveRoot(pathType, relativeSubpath)))
    }
}

/**
 * The outcome of resolving a persisted [SaveLocation] to absolute filesystem paths.
 */
sealed interface SaveLocationResult {
    /**
     * At least one [SaveRoot] of the location resolved to an absolute path inside the container
     * (Requirement 1.3a). [resolvedRoots] carries every root that resolved, paired with its
     * absolute path, in the location's order. Roots that failed to resolve are omitted.
     */
    data class Resolved(
        val resolvedRoots: List<Pair<SaveRoot, java.nio.file.Path>>,
        val saveLocation: SaveLocation,
    ) : SaveLocationResult {
        init {
            require(resolvedRoots.isNotEmpty()) {
                "A Resolved result must carry at least one resolved root"
            }
        }

        /** Convenience: the first resolved root's absolute path. */
        val absolutePath: java.nio.file.Path get() = resolvedRoots.first().second
    }

    /** No save location is persisted for the game (Requirement 1.6). */
    data object Unset : SaveLocationResult

    /**
     * A save location is persisted but none of its roots can be resolved to an absolute path
     * (Requirement 1.7), e.g. every root's [PathType] is outside the supported set or a
     * `SteamUserData` root has no resolvable Steam account id. The persisted value is left
     * unchanged.
     */
    data class Unresolved(val reason: String) : SaveLocationResult
}
