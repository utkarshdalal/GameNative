package app.gamenative.service.epic

import java.io.File
import org.json.JSONObject
import timber.log.Timber

data class EpicInstallState(
    val buildVersion: String,
    val language: String,
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
                stateFile(installPath).writeText(json.toString())
            } catch (e: Exception) {
                Timber.tag("Epic").w(e, "Could not write install state at $installPath")
            }
        }
    }
}
