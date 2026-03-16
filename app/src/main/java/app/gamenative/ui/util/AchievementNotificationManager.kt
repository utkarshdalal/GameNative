package app.gamenative.ui.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class AchievementNotification(val name: String, val iconUrl: String?)

object AchievementNotificationManager {
    private val _notifications = Channel<AchievementNotification>(capacity = Channel.BUFFERED)
    val notifications = _notifications.receiveAsFlow()

    fun show(name: String, iconUrl: String?) {
        _notifications.trySend(AchievementNotification(name, iconUrl))
    }
}
