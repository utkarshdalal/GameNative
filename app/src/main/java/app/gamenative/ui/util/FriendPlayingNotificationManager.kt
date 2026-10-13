package app.gamenative.ui.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class FriendPlayingNotification(
    val friendSteamId: Long,
    val friendName: String,
    val appId: Int,
    val joinable: Boolean,
    val lobbyId: Long,
)

/**
 * One-shot toasts when a Steam friend starts playing the same game you are in.
 */
object FriendPlayingNotificationManager {
    private val _notifications = Channel<FriendPlayingNotification>(capacity = Channel.BUFFERED)
    val notifications = _notifications.receiveAsFlow()

    fun show(notification: FriendPlayingNotification) {
        _notifications.trySend(notification)
    }
}
