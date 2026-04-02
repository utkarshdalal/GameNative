package app.gamenative.service.gog

import android.database.sqlite.SQLiteDatabase
import app.gamenative.service.SessionWatcher
import app.gamenative.ui.util.AchievementNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Polls Comet's local gameplay database for newly unlocked GOG achievements and forwards them to
 * GameNative's native achievement overlay.
 */
class GOGCometAchievementWatcher(
    private val appId: String,
    private val gameplayDatabase: File,
) : SessionWatcher {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val seenAchievementKeys = mutableSetOf<String>()
    private var seeded = false

    override fun start() {
        scope.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed polling Comet gameplay DB for $appId")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Timber.tag(TAG).d("Started Comet achievement watcher for $appId at ${gameplayDatabase.absolutePath}")
    }

    override fun stop() {
        scope.cancel()
        Timber.tag(TAG).d("Stopped Comet achievement watcher for $appId")
    }

    private fun pollOnce() {
        if (!gameplayDatabase.exists()) return

        val unlockedAchievements = readUnlockedAchievements()
        if (!seeded) {
            seenAchievementKeys.addAll(unlockedAchievements.map { it.uniqueKey })
            seeded = true
            Timber.tag(TAG).d(
                "Seeded Comet achievement watcher for $appId with ${seenAchievementKeys.size} existing unlock(s)",
            )
            return
        }

        for (achievement in unlockedAchievements) {
            if (!seenAchievementKeys.add(achievement.uniqueKey)) continue
            AchievementNotificationManager.show(achievement.name, achievement.iconUrl)
            Timber.tag(TAG).i("GOG achievement unlocked for $appId: ${achievement.name}")
        }
    }

    private fun readUnlockedAchievements(): List<UnlockedAchievement> {
        val db = SQLiteDatabase.openDatabase(
            gameplayDatabase.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )

        db.use { database ->
            val cursor = database.rawQuery(
                """
                SELECT key, name, image_url_unlocked, unlock_time
                FROM achievement
                WHERE unlock_time IS NOT NULL AND unlock_time != ''
                ORDER BY unlock_time ASC
                """.trimIndent(),
                null,
            )

            cursor.use {
                val results = mutableListOf<UnlockedAchievement>()
                while (it.moveToNext()) {
                    val key = it.getString(0) ?: continue
                    val name = it.getString(1) ?: key
                    val iconUrl = it.getString(2)
                    val unlockTime = it.getString(3).orEmpty()
                    results.add(
                        UnlockedAchievement(
                            uniqueKey = "$key@$unlockTime",
                            name = name,
                            iconUrl = iconUrl,
                        ),
                    )
                }
                return results
            }
        }
    }

    private data class UnlockedAchievement(
        val uniqueKey: String,
        val name: String,
        val iconUrl: String?,
    )

    companion object {
        private const val TAG = "GOGCometAchievements"
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
