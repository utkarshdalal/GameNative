package app.gamenative.texturepack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class TexturePackSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PrefManager.texturePackEnabled) return Result.success()
        val appId = inputData.getString(KEY_APP_ID)
        if (appId.isNullOrBlank()) return Result.success()
        if (!TexturePackGate.syncEnabled(applicationContext, appId)) return Result.success()

        val container = runCatching { ContainerUtils.getContainer(applicationContext, appId) }.getOrNull()
            ?: return Result.success()
        val cacheDir = TexturePackPaths.cacheDir(container)
        if (!cacheDir.isDirectory) return Result.success()

        val client = TexturePackClient(applicationContext)
        val upload = inputData.getBoolean(KEY_UPLOAD, false)
        val uploadTitle = uploadTitle(container)
        notify(
            if (upload) uploadTitle else applicationContext.getString(R.string.texture_pack_notification_downloading),
            0,
        )
        return try {
            if (upload) {
                uploadSync(client, container, cacheDir, uploadTitle)
            } else {
                downloadSync(client, container, appId, cacheDir)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TexturePackNetworkUnavailable) {
            Timber.i("texture pack sync for $appId deferred: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            Timber.w(e, "texture pack sync for $appId failed")
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
        applicationContext.getString(R.string.texture_pack_notification_downloading),
        0,
    )

    private fun uploadTitle(container: Container): String =
        TexturePackGate.cleanTitle(container.getExtra(TexturePackGate.CONTAINER_EXTRA_TITLE, ""))
            ?.let { applicationContext.getString(R.string.texture_pack_notification_uploading_for, it) }
            ?: applicationContext.getString(R.string.texture_pack_notification_uploading)

    private suspend fun uploadSync(client: TexturePackClient, container: Container, cacheDir: File, title: String) {
        if (TexturePackGate.policyDisabled(TexturePackGate.policyOf(container))) return
        val total = withContext(Dispatchers.IO) { TexturePackSync.sourceFiles(cacheDir).sumOf { it.length() } }
        var done = 0L
        var shownAt = 0L
        notifyBytes(title, 0L, total)
        TexturePackSync.uploadPending(client, container, cacheDir) { bytes ->
            done += bytes
            val now = System.currentTimeMillis()
            if (now - shownAt >= PROGRESS_INTERVAL_MS) {
                shownAt = now
                notifyBytes(title, done, total)
            }
        }
    }

    private fun notifyBytes(title: String, done: Long, total: Long) {
        val text = applicationContext.getString(R.string.texture_pack_progress_bytes, megabytes(done), megabytes(total))
        val percent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
        runCatching {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(title, text, percent, true))
        }
    }

    private fun notifyCount(title: String, done: Long, total: Long) {
        val remaining = (total - done).coerceAtLeast(0L).toInt()
        val text = applicationContext.getString(R.string.texture_pack_notification_remaining, remaining)
        val percent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
        runCatching {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(title, text, percent, false))
        }
    }

    private fun megabytes(bytes: Long): String =
        String.format(java.util.Locale.ROOT, "%.1f", bytes.toDouble() / (1024.0 * 1024.0))

    private suspend fun notify(title: String, remaining: Int) {
        runCatching { setForeground(foregroundInfo(title, remaining)) }
    }

    private fun foregroundInfo(title: String, remaining: Int): ForegroundInfo {
        val notification = buildNotification(title, remaining)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, remaining: Int): Notification {
        val text = if (remaining > 0) {
            applicationContext.getString(R.string.texture_pack_notification_remaining, remaining)
        } else {
            null
        }
        return buildNotification(title, text, -1, false)
    }

    private fun buildNotification(title: String, text: String?, percent: Int, upload: Boolean): Notification {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.texture_pack_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(if (upload) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .apply {
                if (text != null) setContentText(text)
                if (percent >= 0) setProgress(100, percent, false)
            }
            .addAction(0, applicationContext.getString(android.R.string.cancel), cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private suspend fun downloadSync(client: TexturePackClient, container: Container, appId: String, cacheDir: File) {
        val fingerprint = TexturePackSync.ensureFingerprint(client, container)
        if (fingerprint.isBlank()) return
        val pack = TexturePackSync.fetchPack(client, container, fingerprint)
        if (TexturePackGate.policyDisabled(pack.policy)) return
        val keys = withContext(Dispatchers.IO) {
            TexturePackSync.downloadKeys(cacheDir, pack.entries, pack.pendingKeys)
        }
        if (keys.isEmpty()) {
            recordServerEntries(container, cacheDir, pack)
            return
        }
        val title = applicationContext.getString(R.string.texture_pack_notification_downloading)
        notify(title, keys.size)
        val total = keys.size.toLong()
        val done = AtomicLong(0L)
        val shownAt = AtomicLong(0L)
        TexturePackSync.downloadEntries(client, cacheDir, keys) { _, _ ->
            val count = done.incrementAndGet()
            val now = System.currentTimeMillis()
            val last = shownAt.get()
            if (now - last >= PROGRESS_INTERVAL_MS && shownAt.compareAndSet(last, now)) {
                notifyCount(title, count, total)
            }
        }
        recordServerEntries(container, cacheDir, pack)
    }

    private suspend fun recordServerEntries(container: Container, cacheDir: File, pack: PackResponse) {
        val count = withContext(Dispatchers.IO) {
            TexturePackSync.readyServerEntries(cacheDir, pack.entries, pack.pendingKeys)
        }
        TexturePackSync.recordServerEntries(container, count)
    }

    companion object {
        private const val KEY_APP_ID = "appId"
        private const val KEY_UPLOAD = "upload"
        private const val MAX_ATTEMPTS = 3
        private const val CHANNEL_ID = "texture_pack_transfer"
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_ID = 1201

        fun networkType(allowMobileData: Boolean): NetworkType =
            if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED

        private fun constraints(): Constraints =
            Constraints.Builder().setRequiredNetworkType(networkType(PrefManager.texturePackAllowMobileData)).build()

        fun enqueueDownloadSync(context: Context, appId: String) {
            if (!PrefManager.texturePackEnabled) return
            val request = OneTimeWorkRequestBuilder<TexturePackSyncWorker>()
                .setConstraints(constraints())
                .setInputData(Data.Builder().putString(KEY_APP_ID, appId).build())
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork("texture-pack-download-$appId", ExistingWorkPolicy.KEEP, request)
            }.onFailure { Timber.w(it, "could not enqueue texture pack download for $appId") }
        }

        fun enqueueUploadSync(context: Context, appId: String) {
            if (!PrefManager.texturePackEnabled) return
            val request = OneTimeWorkRequestBuilder<TexturePackSyncWorker>()
                .setConstraints(constraints())
                .setInputData(Data.Builder().putString(KEY_APP_ID, appId).putBoolean(KEY_UPLOAD, true).build())
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork("texture-pack-upload-$appId", ExistingWorkPolicy.KEEP, request)
            }.onFailure { Timber.w(it, "could not enqueue texture pack upload for $appId") }
        }
    }
}
