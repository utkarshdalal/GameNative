package app.gamenative.service.epic

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class EpicInstallState(
    val buildVersion: String,
    val language: String,
    val prereqIds: List<String> = emptyList(),
    val prereqName: String = "",
    val prereqPath: String = "",
) {
    companion object {
        private const val STATE_FILE_NAME = ".gamenative_epic_install.json"

        private fun stateFile(installPath: String): File = File(installPath, STATE_FILE_NAME)

        fun read(installPath: String): EpicInstallState? {
            if (installPath.isEmpty()) return null
            val file = stateFile(installPath)
            if (!file.isFile) return null
            return try {
                val json = JSONObject(file.readText())
                EpicInstallState(
                    buildVersion = json.optString("buildVersion", ""),
                    language = json.optString("language", ""),
                    prereqIds = json.optJSONArray("prereqIds")
                        ?.let { ids -> List(ids.length()) { ids.optString(it) } }
                        .orEmpty(),
                    prereqName = json.optString("prereqName", ""),
                    prereqPath = json.optString("prereqPath", ""),
                )
            } catch (e: Exception) {
                Timber.tag("Epic").w(e, "Could not read install state at $installPath")
                null
            }
        }

        fun write(installPath: String, state: EpicInstallState) {
            if (installPath.isEmpty()) return
            try {
                val json = JSONObject()
                    .put("buildVersion", state.buildVersion)
                    .put("language", state.language)
                    .put("prereqIds", JSONArray(state.prereqIds))
                    .put("prereqName", state.prereqName)
                    .put("prereqPath", state.prereqPath)
                stateFile(installPath).writeText(json.toString())
            } catch (e: Exception) {
                Timber.tag("Epic").w(e, "Could not write install state at $installPath")
            }
        }
    }
}
