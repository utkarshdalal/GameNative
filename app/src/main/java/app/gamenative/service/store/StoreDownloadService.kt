package app.gamenative.service.store

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.gamenative.service.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

/** Keeps the process in the foreground while a [StoreGames] download runs. */
@AndroidEntryPoint
class StoreDownloadService : Service() {
    @Inject
    lateinit var notificationHelper: NotificationHelper

    companion object {
        private const val NOTIFICATION_ID = 5

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, StoreDownloadService::class.java)) }
                .onFailure { Timber.w(it, "Store download service could not start") }
        }

        /** Goes through onStartCommand so the service always reaches startForeground before it stops. */
        fun stopIfIdle(context: Context) {
            if (StoreGames.activeDownloads.isNotEmpty()) return
            val intent = Intent(context, StoreDownloadService::class.java)
            runCatching { context.startForegroundService(intent) }.onFailure { context.stopService(intent) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationHelper.createServiceNotification(NOTIFICATION_ID, getString(app.gamenative.R.string.download_preparing))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Timber.w(e, "Store downloads continue without a foreground notification")
        }
        if (StoreGames.activeDownloads.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
