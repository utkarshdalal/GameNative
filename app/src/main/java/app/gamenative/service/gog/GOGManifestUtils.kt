package app.gamenative.service.gog

import org.json.JSONObject
import timber.log.Timber
import java.io.File

object GOGManifestUtils {
    const val MANIFEST_FILE_NAME = "_gog_manifest.json"
    private const val KEY_SCRIPT_INTERPRETER = "scriptInterpreter"
    private const val KEY_DEPENDENCIES = "dependencies"
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
}
