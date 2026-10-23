package app.gamenative.service

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.ui.util.AchievementNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Shows the unlock toast for games running under the headless Steam host, which reports new
 * unlocks over its command socket. The real client stores the unlock itself, so nothing is
 * uploaded from here.
 */
class SteamHostAchievementPoller(
    private val appId: Int,
    context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notifiedNames = mutableSetOf<String>()
    private val soundPlayer = AchievementSoundPlayer(context)
    private var displayNameMap: Map<String, String> = emptyMap()
    private var iconUrlMap: Map<String, String?> = emptyMap()

    fun start() {
        Timber.tag("achievements").d("SteamHostAchievementPoller started for appId=$appId")
        scope.launch {
            loadSchema()
            while (isActive) {
                for (unlock in SteamOverlayClient.pollAchievementUnlocks()) {
                    if (!notifiedNames.add(unlock.apiName)) continue
                    if (unlock.apiName !in displayNameMap) loadSchema()
                    val displayName = displayNameMap[unlock.apiName] ?: unlock.apiName
                    if (PrefManager.achievementShowNotification) {
                        AchievementNotificationManager.show(displayName, iconUrlMap[unlock.apiName])
                    }
                    if (PrefManager.achievementPlaySound) {
                        soundPlayer.play()
                    }
                    Timber.tag("achievements").i("Achievement unlocked: ${unlock.apiName} ($displayName) at ${unlock.unlockTime}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun loadSchema() {
        val achievements = SteamService.fetchAchievementsForDisplay(appId)
            .orEmpty()
            .mapNotNull { ach -> ach.name?.takeIf { it.isNotEmpty() }?.let { it to ach } }
        if (achievements.isEmpty()) {
            Timber.tag("achievements").w("No achievement schema for appId=$appId, popups will show api names")
            return
        }
        displayNameMap = achievements.associate { (name, ach) -> name to ach.displayName.ifEmpty { name } }
        iconUrlMap = achievements.associate { (name, ach) -> name to ach.icon.takeIf { it.isNotEmpty() } }
    }

    fun stop() {
        scope.cancel()
        soundPlayer.release()
        Timber.tag("achievements").d("SteamHostAchievementPoller stopped")
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
