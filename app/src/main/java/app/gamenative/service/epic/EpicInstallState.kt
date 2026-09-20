package app.gamenative.service.epic

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class EpicInstallState(
    val appName: String,
    val buildVersion: String,
    val files: Set<String>,
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
                val filesArray = json.optJSONArray("files") ?: JSONArray()
                val files = buildSet {
                    for (i in 0 until filesArray.length()) {
                        add(filesArray.getString(i))
                    }
                }
                EpicInstallState(
                    appName = json.optString("appName", ""),
                    buildVersion = json.optString("buildVersion", ""),
                    files = files,
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
                    .put("appName", state.appName)
                    .put("buildVersion", state.buildVersion)
                    .put("files", JSONArray(state.files.toList()))
                val target = stateFile(installPath)
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(json.toString())
                if (!tmp.renameTo(target)) {
                    target.writeText(json.toString())
                    tmp.delete()
                }
            } catch (e: Exception) {
                Timber.tag("Epic").w(e, "Could not write install state at $installPath")
            }
        }

        fun removeFilesNoLongerSelected(
            installPath: String,
            appName: String,
            previous: EpicInstallState?,
            currentFiles: Set<String>,
        ): Int {
            if (previous == null || installPath.isEmpty() || previous.appName != appName) return 0
            val stale = previous.files - currentFiles
            if (stale.isEmpty()) return 0

            val installDir = File(installPath)
            val installRoot = try {
                installDir.canonicalPath
            } catch (e: Exception) {
                Timber.tag("Epic").w(e, "Could not resolve install dir $installPath, skipping cleanup")
                return 0
            }

            var removed = 0
            val emptyDirCandidates = mutableSetOf<File>()
            for (relativePath in stale) {
                val file = File(installDir, relativePath)
                val resolved = try {
                    file.canonicalPath
                } catch (e: Exception) {
                    continue
                }
                if (!resolved.startsWith("$installRoot${File.separator}")) continue
                if (!file.isFile) continue
                if (file.delete()) {
                    removed++
                    file.parentFile?.let { emptyDirCandidates.add(it) }
                }
            }

            emptyDirCandidates.forEach { pruneEmptyDirs(it, installRoot) }

            Timber.tag("Epic").i("Removed $removed file(s) no longer selected in $installPath")
            return removed
        }

        private fun pruneEmptyDirs(dir: File, installRoot: String) {
            var current: File? = dir
            while (current != null) {
                val resolved = try {
                    current.canonicalPath
                } catch (e: Exception) {
                    return
                }
                if (!resolved.startsWith("$installRoot${File.separator}")) return
                if (current.list()?.isEmpty() != true) return
                if (!current.delete()) return
                current = current.parentFile
            }
        }
    }
}
