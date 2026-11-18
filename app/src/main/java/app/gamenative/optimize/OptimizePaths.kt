package app.gamenative.optimize

import java.io.File

/** Where a game's settings can live: the install folder, the Wine prefix and the prefix user's home. */
class PathRoots(
    val install: File?,
    val prefix: File?,
    val userHome: File?,
    val steamAccountId: Long? = null,
)

/**
 * Resolves the `{placeholder}/a/b` paths levers carry. Segments match case-insensitively. `*` is exactly one folder:
 * the newest one under which the rest of the path exists (for [resolve]'s createIfMissing, whose parent folder
 * exists). `**` is the newest file matching the rest of the path in any subfolder. A path with an unknown
 * placeholder, a wildcard nothing matches, or one that leaves the prefix and the install folder resolves to null.
 */
object OptimizePaths {

    private const val EXE_SEARCH_DEPTH = 4
    private const val DEEP_SEARCH_DEPTH = 6
    private const val USER_NAME = "xuser"

    private val PLACEHOLDER = Regex("^\\{([A-Za-z]+)(?::([^}]*))?\\}")

    fun resolve(roots: PathRoots, template: String, createIfMissing: Boolean = false): File? {
        val userName = roots.userHome?.name ?: USER_NAME
        val normalized = template.replace('\\', '/').replace("{userName}", userName)
        val match = PLACEHOLDER.find(normalized) ?: return null
        val base = base(roots, match.groupValues[1], match.groupValues[2]) ?: return null
        val rest = normalized.substring(match.range.last + 1)
        if (rest.contains('{') || rest.contains('}')) return null
        val segments = rest.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        val target = walk(base, segments, createIfMissing) ?: return null
        return target.takeIf { allowed(roots, it) }
    }

    private fun base(roots: PathRoots, name: String, arg: String): File? {
        val home = roots.userHome
        val prefix = roots.prefix
        return when (name) {
            "localAppDataPath" -> home?.let { child(child(it, "AppData"), "Local") }
            "roamingAppDataPath" -> home?.let { child(child(it, "AppData"), "Roaming") }
            "localLowAppDataPath" -> home?.let { child(child(it, "AppData"), "LocalLow") }
            "documentsPath" -> home?.let { child(it, "Documents") }
            "userProfilePath" -> home
            "programDataPath" -> prefix?.let { child(child(it, "drive_c"), "ProgramData") }
            "systemDrive" -> prefix?.let { child(it, "drive_c") }
            "gameInstallPath" -> roots.install
            "steamUserdataPath" -> {
                val account = roots.steamAccountId ?: return null
                val steam = prefix?.let { walk(it, listOf("drive_c", "Program Files (x86)", "Steam", "userdata"), false) } ?: return null
                child(steam, account.toString())
            }
            "exeDir" -> {
                val install = roots.install ?: return null
                if (arg.isEmpty()) return null
                findExeDir(install, arg)
            }
            else -> null
        }
    }

    private fun findExeDir(install: File, exe: String): File? {
        val wanted = exe.replace('\\', '/').substringAfterLast('/')
        var level = listOf(install)
        for (depth in 0..EXE_SEARCH_DEPTH) {
            for (dir in level) {
                if (dir.listFiles()?.any { it.isFile && it.name.equals(wanted, ignoreCase = true) } == true) return dir
            }
            level = level.flatMap { dir -> dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty() }
            if (level.isEmpty()) break
        }
        return null
    }

    internal fun child(dir: File, name: String): File {
        val exact = File(dir, name)
        if (exact.exists()) return exact
        return dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: exact
    }

    private fun walk(start: File, segments: List<String>, createIfMissing: Boolean): File? {
        if (segments.none { it.isWildcard() }) return segments.fold(start) { dir, seg -> child(dir, seg) }
        return wild(start, segments, createIfMissing)
    }

    private fun String.isWildcard(): Boolean = this == "*" || this == "**"

    /** A path with a wildcard: only a match that exists (or can be created next to an existing folder) counts. */
    private fun wild(start: File, segments: List<String>, createIfMissing: Boolean): File? {
        val at = segments.indexOfFirst { it.isWildcard() }
        if (at < 0) {
            val target = segments.fold(start) { dir, seg -> child(dir, seg) }
            return target.takeIf { usable(it, createIfMissing) }
        }
        val head = segments.subList(0, at).fold(start) { dir, seg -> child(dir, seg) }
        val rest = segments.subList(at + 1, segments.size)
        if (segments[at] == "**") return deepNewest(head, rest)
        val dirs = head.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (rest.isEmpty()) return dirs.firstOrNull()
        return dirs.firstNotNullOfOrNull { wild(it, rest, createIfMissing) }
    }

    private fun usable(file: File, createIfMissing: Boolean): Boolean =
        file.isFile || (createIfMissing && !file.exists() && file.parentFile?.isDirectory == true)

    private fun deepNewest(start: File, rest: List<String>): File? {
        if (rest.isEmpty() || rest.any { it.isWildcard() }) return null
        val found = mutableListOf<File>()
        var level = listOf(start)
        for (depth in 0..DEEP_SEARCH_DEPTH) {
            for (dir in level) {
                val candidate = rest.fold(dir) { d, s -> child(d, s) }
                if (candidate.isFile) found += candidate
            }
            level = level.flatMap { dir -> dir.listFiles()?.filter { it.isDirectory }.orEmpty() }
            if (level.isEmpty()) break
        }
        return found.maxByOrNull { it.lastModified() }
    }

    private fun inside(root: File, file: File): Boolean {
        val rootPath = root.canonicalPath
        val path = file.canonicalPath
        return path.startsWith(rootPath + File.separator)
    }

    /** Folders under the user home count through their link targets, since Wine links Documents and friends out. */
    fun allowed(roots: PathRoots, file: File): Boolean {
        val bases = listOfNotNull(roots.prefix, roots.userHome, roots.install).toMutableList()
        roots.userHome?.listFiles()?.filter { it.isDirectory }?.let { bases += it }
        return bases.any { inside(it, file) }
    }
}
