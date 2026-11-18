package app.gamenative.service.gog

import app.gamenative.service.gog.api.GOGManifestParser
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

object GOGManifestUtils {
    const val MANIFEST_FILE_NAME = "_gog_manifest.json"
    const val FILE_LIST_FILE_NAME = "_gog_files.json"
    private const val KEY_SCRIPT_INTERPRETER = "scriptInterpreter"
    private const val KEY_DEPENDENCIES = "dependencies"
    const val KEY_BUILD_ID = "buildId"
    const val KEY_BRANCH = "branch"
    private const val KEY_FILES = "files"
    private val INFO_FILE_REGEX = Regex("""^goggame-(\d+)\.info$""")

    fun isInfoFile(file: File): Boolean = file.isFile && INFO_FILE_REGEX.matches(file.name)

    /** Product ids of every goggame-<id>.info file directly inside [dir]. */
    fun infoFileProductIds(dir: File): Set<String> {
        val files = dir.listFiles() ?: return emptySet()
        return files.filter { it.isFile }
            .mapNotNull { INFO_FILE_REGEX.matchEntire(it.name)?.groupValues?.get(1) }
            .toSet()
    }

    /**
     * Picks the base game's info file among [infoFiles]. With [gameId], goggame-<gameId>.info wins
     * whatever its rootGameId; otherwise a readable file whose rootGameId equals its gameId, then a
     * readable one that names no other product as its root, else null. Without [gameId], a file
     * whose rootGameId equals its gameId, else the first by name.
     */
    fun selectBaseInfoFile(infoFiles: List<File>, gameId: String?): File? {
        if (infoFiles.isEmpty()) return null
        val sorted = infoFiles.sortedBy { it.name }
        if (gameId != null) {
            sorted.firstOrNull { it.name == "goggame-$gameId.info" }?.let { return it }
            val readable = sorted.mapNotNull { file -> readInfoJson(file)?.let { file to it } }
            return readable.firstOrNull { isBaseInfo(it.second) }?.first
                ?: readable.firstOrNull { !isDlcInfo(it.second) }?.first
        }
        return sorted.firstOrNull { isBaseInfoFile(it) } ?: sorted.first()
    }

    fun isBaseInfoFile(infoFile: File): Boolean = readInfoJson(infoFile)?.let { isBaseInfo(it) } == true

    private fun readInfoJson(infoFile: File): JSONObject? =
        try {
            JSONObject(infoFile.readText())
        } catch (e: Exception) {
            null
        }

    private fun isBaseInfo(json: JSONObject): Boolean {
        val id = json.optString("gameId", "")
        return id.isNotEmpty() && id == json.optString("rootGameId", "")
    }

    private fun isDlcInfo(json: JSONObject): Boolean {
        val root = json.optString("rootGameId", "")
        return root.isNotEmpty() && root != json.optString("gameId", "")
    }

    fun readLocalManifest(installDir: File): JSONObject? {
        val manifestFile = File(installDir, MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) return null
        return try {
            JSONObject(manifestFile.readText())
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Failed to parse _gog_manifest.json")
            null
        }
    }

    fun needsScriptInterpreter(installDir: File): Boolean {
        val root = readLocalManifest(installDir) ?: return false
        return root.optBoolean(KEY_SCRIPT_INTERPRETER, false)
    }

    fun readDependencies(installDir: File): List<String>? {
        val array = readLocalManifest(installDir)?.optJSONArray(KEY_DEPENDENCIES) ?: return null
        return List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    }

    /** Records the install-relative paths of the depot files [buildId] wrote, in installDir/_gog_files.json. */
    fun writeRecordedFiles(installDir: File, buildId: String, files: List<String>) {
        val root = JSONObject().apply {
            put(KEY_BUILD_ID, buildId)
            put(KEY_FILES, JSONArray(files))
        }
        File(installDir, FILE_LIST_FILE_NAME).writeText(root.toString())
    }

    /** Install-relative paths of the depot files [buildId] wrote, or null when none are recorded for that build. */
    fun readRecordedFiles(installDir: File, buildId: String): List<String>? {
        val file = File(installDir, FILE_LIST_FILE_NAME)
        if (!file.exists()) return null
        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Failed to parse $FILE_LIST_FILE_NAME")
            return null
        }
        if (root.optString(KEY_BUILD_ID, "") != buildId) return null
        val array = root.optJSONArray(KEY_FILES) ?: return null
        return List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
    }

    /** Paths in [oldPaths] that [newPaths] no longer has (case-insensitive), skipping paths that would leave the install folder. */
    fun removedFiles(oldPaths: List<String>, newPaths: Collection<String>): List<String> {
        val kept = newPaths.mapTo(HashSet()) { GOGManifestParser.pathKey(it) }
        return oldPaths
            .filter { GOGManifestParser.isSafeRelativePath(it) && GOGManifestParser.pathKey(it) !in kept }
            .distinctBy { GOGManifestParser.pathKey(it) }
    }

    /** Deletes each regular file of [paths] that resolves inside [installDir]; never follows a symlink. Returns how many were deleted. */
    fun deleteFiles(installDir: File, paths: List<String>): Int {
        val root = installDir.canonicalFile
        return paths.count { path ->
            val file = File(root, path)
            val parent = file.parentFile?.canonicalFile ?: return@count false
            val inside = parent == root || parent.path.startsWith(root.path + File.separator)
            inside && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.delete()
        }
    }
}
