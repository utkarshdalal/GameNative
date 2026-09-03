package app.gamenative.ui.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/** A "screenshot saved" toast request: the captured file and its owning appId. */
data class ScreenshotNotification(val file: File, val appId: String)

/**
 * Broadcasts "screenshot saved" events to [app.gamenative.ui.component.ScreenshotToastOverlay].
 * Mirrors [AchievementNotificationManager].
 */
object ScreenshotNotificationManager {
    private val _notifications = Channel<ScreenshotNotification>(capacity = Channel.BUFFERED)
    val notifications = _notifications.receiveAsFlow()

    fun show(file: File, appId: String) {
        _notifications.trySend(ScreenshotNotification(file, appId))
    }
}
