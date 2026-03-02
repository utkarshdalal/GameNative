package app.gamenative.service.gog

import org.json.JSONObject
import timber.log.Timber
import java.io.File

object GOGManifestUtils {
    const val MANIFEST_FILE_NAME = "_gog_manifest.json"
    private const val KEY_SCRIPT_INTERPRETER = "scriptInterpreter"

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
}
