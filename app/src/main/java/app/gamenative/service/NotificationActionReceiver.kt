package app.gamenative.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import timber.log.Timber

/**
 * Handles user-tapped actions on foreground-service notifications.
 *
 * Previously the Exit action used `PendingIntent.getForegroundService` targeting
 * [SteamService]. That meant tapping Exit on a GOG/Epic/Amazon (or group summary)
 * notification while [SteamService] wasn't running would trigger
 * `startForegroundService(SteamService)`, but SteamService's ACTION_EXIT branch
 * never calls `startForeground(...)` — leading to a `ForegroundServiceDidNotStartInTime`
 * crash on Android 12+.
 *
 * Routing through a regular broadcast avoids the FGS contract entirely. Every
 * running service is already subscribed to [AndroidEvent.EndProcess] and stops
 * itself when the event fires.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NotificationHelper.ACTION_EXIT -> {
                Timber.d("NotificationActionReceiver: Exit tapped, broadcasting EndProcess")
                PluviaApp.events.emit(AndroidEvent.EndProcess)
            }
        }
    }
}
