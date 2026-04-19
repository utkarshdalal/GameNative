package app.gamenative.service

import android.os.FileObserver
import app.gamenative.ui.util.AchievementNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.gamenative.PrefManager
import org.json.JSONObject
import timber.log.Timber
import java.io.File

class AchievementWatcher(
    private val appId: Int,
    private val watchDirs: List<File>,
    private val displayNameMap: Map<String, String>,
    private val iconUrlMap: Map<String, String?>,
    private val configDirectory: String?,
) {
    private val observers = mutableListOf<FileObserver>()
    private val notifiedNames = mutableSetOf<String>()
    private val uploadedNames = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var uploadJob: Job? = null

    fun start() {
        // Snapshot all currently earned achievements so we don't notify for
        // pre-existing unlocks when the game writes its initial achievements.json.
        for (dir in watchDirs) {
            dir.mkdirs()
            val achFile = File(dir, "achievements.json")
            if (achFile.exists()) {
                try {
                    val json = JSONObject(achFile.readText(Charsets.UTF_8))
                    for (achievementName in json.keys()) {
                        val entry = json.optJSONObject(achievementName) ?: continue
                        if (entry.optBoolean("earned", false)) {
                            notifiedNames.add(achievementName)
                            uploadedNames.add(achievementName)
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("achievements").w(e, "Failed to snapshot existing achievements.json in ${dir.absolutePath}")
                }
            }
        }
        Timber.tag("achievements").d("AchievementWatcher seeded ${notifiedNames.size} pre-existing achievements")

        // Start Watching for the specific achievement JSON file changes
        for (dir in watchDirs) {
            val observer = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "achievements.json") {
                        checkForNewUnlocks(File(dir, "achievements.json"))
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
        Timber.tag("achievements").d("AchievementWatcher started, watching ${watchDirs.size} dirs")
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        scope.cancel()
        Timber.tag("achievements").d("AchievementWatcher stopped")
    }

    private fun checkForNewUnlocks(achFile: File) {
        if (!achFile.exists()) return
        var hasNewUnlocks = false
        try {
            val json = JSONObject(achFile.readText(Charsets.UTF_8))
            for (name in json.keys()) {
                val entry = json.optJSONObject(name) ?: continue
                if (!entry.optBoolean("earned", false)) continue
                if (name in notifiedNames) continue

                notifiedNames.add(name)
                hasNewUnlocks = true
                val displayName = displayNameMap[name] ?: name
                val iconUrl = iconUrlMap[name]

                if(PrefManager.achievementShowNotification) {
                    AchievementNotificationManager.show(displayName, iconUrl)
                }
                Timber.tag("achievements").i("Achievement unlocked: $name ($displayName)")
            }
        } catch (e: Exception) {
            Timber.tag("achievements").w(e, "Failed to parse achievements.json for watcher")
        }

        if (hasNewUnlocks) {
            scheduleUpload()
        }
    }

    /**
     * Debounces achievement uploads: waits 5 seconds after the last unlock before uploading to stop server spam
     */
    private fun scheduleUpload() {
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(UPLOAD_DEBOUNCE_MS)
            uploadToSteam()
        }
    }

    private suspend fun uploadToSteam() {
        if (configDirectory == null) {
            Timber.tag("achievements").w("No configDirectory set, skipping real-time achievement upload for appId=$appId")
            return
        }

        if (!SteamService.isConnected) {
            Timber.tag("achievements").w("Not connected to Steam, skipping real-time achievement upload for appId=$appId")
            return
        }

        // Get unlocked and stats
        val (allUnlocked, gseStatsDir) = SteamService.collectGseUnlocksAndStats(watchDirs)

        val newToUpload = allUnlocked - uploadedNames

        Timber.tag("achievements").d("Real-time uploading ${newToUpload.size} new achievements (${allUnlocked.size} total) for appId=$appId")
        val result = SteamService.storeAchievementUnlocks(appId, configDirectory, allUnlocked, gseStatsDir ?: watchDirs.first().resolve("stats"))
        result.onSuccess {
            uploadedNames.addAll(allUnlocked)
            Timber.tag("achievements").i("Real-time achievement upload succeeded for appId=$appId")
        }.onFailure { e ->
            Timber.tag("achievements").e(e, "Real-time achievement upload failed for appId=$appId, will retry on next unlock or at exit")
        }
    }

    companion object {
        private const val UPLOAD_DEBOUNCE_MS = 5_000L
    }
}
