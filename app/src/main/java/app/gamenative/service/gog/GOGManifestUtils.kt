package app.gamenative.service.gog

import org.json.JSONObject
import timber.log.Timber
import java.io.File

object GOGManifestUtils {
    const val MANIFEST_FILE_NAME = "_gog_manifest.json"
    private const val KEY_SCRIPT_INTERPRETER = "scriptInterpreter"
    private const val KEY_DEPENDENCIES = "dependencies"

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
        return List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
    }
}
