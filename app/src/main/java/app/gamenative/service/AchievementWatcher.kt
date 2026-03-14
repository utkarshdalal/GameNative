package app.gamenative.service

import android.os.FileObserver
import app.gamenative.ui.util.AchievementNotificationManager
import org.json.JSONObject
import timber.log.Timber
import java.io.File

class AchievementWatcher(
    private val watchDirs: List<File>,
    private val displayNameMap: Map<String, String>,
    private val iconUrlMap: Map<String, String?>,
) {
    private val observers = mutableListOf<FileObserver>()
    private val notifiedNames = mutableSetOf<String>()
    private var sessionStartTime: Long = 0L

    fun start() {
        sessionStartTime = System.currentTimeMillis() / 1000

        for (dir in watchDirs) {
            dir.mkdirs()
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
        Timber.d("AchievementWatcher started, watching ${watchDirs.size} dirs")
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        Timber.d("AchievementWatcher stopped")
    }

    private fun checkForNewUnlocks(achFile: File) {
        if (!achFile.exists()) return
        try {
            val json = JSONObject(achFile.readText(Charsets.UTF_8))
            for (name in json.keys()) {
                val entry = json.optJSONObject(name) ?: continue
                if (!entry.optBoolean("earned", false)) continue
                if (name in notifiedNames) continue

                val earnedTime = entry.optLong("earned_time", 0L)
                if (earnedTime >= sessionStartTime - 30) {
                    notifiedNames.add(name)
                    val displayName = displayNameMap[name] ?: name
                    val iconUrl = iconUrlMap[name]
                    AchievementNotificationManager.show(displayName, iconUrl)
                    Timber.i("Achievement unlocked: $name ($displayName)")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse achievements.json for watcher")
        }
    }
}
