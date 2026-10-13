package app.gamenative.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import app.gamenative.ui.screen.support.SupportProgressText
import app.gamenative.ui.screen.support.SupportReplyWatcher
import app.gamenative.utils.LocaleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class SupportReplyWatchService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var job: Job? = null
    private var localized: Context? = null

    private fun text(): Context = localized ?: this

    override fun onCreate() {
        super.onCreate()
        try {
            localized = LocaleHelper.applyLanguage(this, PrefManager.appLanguage)
            NotificationHelper.createSupportChannels(this)
        } catch (e: Exception) {
            Timber.tag(TAG).w("setup failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        try {
            startForeground(NotificationHelper.NOTIFICATION_ID_SUPPORT_WAIT, waitNotification(conversationId, null))
        } catch (e: Exception) {
            Timber.tag(TAG).w("startForeground failed: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (conversationId.isNullOrEmpty()) {
            finish()
            return START_NOT_STICKY
        }
        val game = intent?.getStringExtra(EXTRA_GAME).orEmpty()
        val baseline = intent?.getLongExtra(EXTRA_BASELINE, 0L) ?: 0L
        job?.cancel()
        watching.value = conversationId
        job = scope.launch {
            watch(conversationId, game, baseline)
            finish()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        finish()
    }

    override fun onDestroy() {
        watching.value = null
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        job?.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Timber.tag(TAG).w("stopForeground failed: ${e.javaClass.simpleName}")
        }
        stopSelf()
    }

    private suspend fun watch(conversationId: String, game: String, baseline: Long) {
        try {
            val deadline = SystemClock.elapsedRealtime() + MAX_WATCH_MS
            var sawWaiting = false
            while (SystemClock.elapsedRealtime() < deadline) {
                when (val result = SupportApi.getConversation(conversationId)) {
                    is ApiResult.Success -> {
                        val conversation = result.data
                        if (conversation.state == SupportApi.STATE_WAITING) {
                            if (conversation.progress?.stage == SupportApi.STAGE_FAILED) return
                            sawWaiting = true
                            updateWait(conversationId, conversation.progress)
                        } else if (sawWaiting || conversation.lastMessageAt > baseline) {
                            postReply(conversationId, conversation.game.ifEmpty { game })
                            return
                        }
                    }
                    is ApiResult.HttpError -> if (result.code in STOP_CODES) return
                    is ApiResult.NetworkError -> Unit
                }
                delay(POLL_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("watch failed: ${e.javaClass.simpleName}")
        }
    }

    private fun updateWait(conversationId: String, progress: SupportApi.Progress?) {
        val line = SupportProgressText.line(
            text().resources,
            progress,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis(),
        )
        notificationManager().notify(
            NotificationHelper.NOTIFICATION_ID_SUPPORT_WAIT,
            waitNotification(conversationId, line),
        )
    }

    private fun postReply(conversationId: String, game: String) {
        SupportReplyWatcher.kick()
        if (SupportReplyWatcher.isChatVisible(conversationId)) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val context = text()
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SUPPORT_REPLIES)
            .setContentTitle(context.getString(R.string.support_reply_ready))
            .setContentText(game.ifEmpty { context.getString(R.string.support_reply_ready_tap) })
            .setSmallIcon(smallIcon())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSilent(PluviaApp.xEnvironment != null && PluviaApp.isActivityInForeground)
            .setContentIntent(openIntent(this, conversationId))
            .build()
        notificationManager().notify(NotificationHelper.NOTIFICATION_ID_SUPPORT_REPLY, notification)
    }

    private fun waitNotification(conversationId: String?, line: String?): Notification {
        val context = text()
        val content = line ?: context.getString(R.string.support_wait_text)
        val stopIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_STOP,
            Intent(this, NotificationActionReceiver::class.java).setAction(NotificationHelper.ACTION_SUPPORT_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SUPPORT_WAIT)
            .setContentTitle(context.getString(R.string.support_wait_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(smallIcon())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(0, context.getString(R.string.support_wait_stop), stopIntent)
        if (!conversationId.isNullOrEmpty()) builder.setContentIntent(openIntent(this, conversationId))
        return builder.build()
    }

    private fun smallIcon(): Int =
        if (PrefManager.useAltNotificationIcon) R.drawable.ic_notification_alt else R.drawable.ic_notification

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "SupportReplyWatch"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_GAME = "game"
        private const val EXTRA_BASELINE = "baseline"
        private const val POLL_MS = 30_000L
        private const val MAX_WATCH_MS = 25 * 60 * 1000L
        private const val REQUEST_STOP = 7301
        private const val REQUEST_OPEN = 7302
        private val STOP_CODES = setOf(401, 403, 404)

        val watching = mutableStateOf<String?>(null)

        fun canNotify(context: Context): Boolean =
            try {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            } catch (e: Exception) {
                false
            }

        fun start(context: Context, conversationId: String, game: String, baseline: Long) {
            try {
                val appContext = context.applicationContext
                if (!canNotify(appContext)) return
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, SupportReplyWatchService::class.java)
                        .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                        .putExtra(EXTRA_GAME, game)
                        .putExtra(EXTRA_BASELINE, baseline),
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w("start failed: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, SupportReplyWatchService::class.java))
            } catch (e: Exception) {
                Timber.tag(TAG).w("stop failed: ${e.javaClass.simpleName}")
            }
        }

        private fun openIntent(context: Context, conversationId: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SUPPORT
                putExtra(MainActivity.EXTRA_SUPPORT_CONVERSATION, conversationId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
