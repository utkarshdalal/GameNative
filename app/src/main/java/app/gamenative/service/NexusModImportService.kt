package app.gamenative.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.gamenative.MainActivity
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.mods.ModImportProgress
import app.gamenative.mods.NexusModFile
import app.gamenative.mods.NexusModInfo
import app.gamenative.mods.NexusModManager
import app.gamenative.mods.NexusModReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NexusModImportService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val delayedStop = Runnable {
        if (activeTasks.get() <= 0 && pendingTasks.isEmpty() && !resumeInProgress.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cancelDelayedStop()
        startForeground(NOTIFICATION_ID, createNotification("Preparing Nexus mod import"))
        if (intent?.action == ACTION_RUN_IMPORT) {
            startQueuedTask(intent.getStringExtra(EXTRA_TASK_ID), intent)
        } else if (intent == null || intent.action == ACTION_RESUME_IMPORTS) {
            resumeInterruptedImports()
        } else if (activeTasks.get() == 0 && pendingTasks.isEmpty()) {
            scheduleStopIfIdle()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(delayedStop)
        super.onDestroy()
        scope.cancel()
    }

    private fun startQueuedTask(taskId: String?, intent: Intent) {
        val task = taskId?.let { pendingTasks.remove(it) }
        val request = task?.request ?: intent.toImportRequest()
        if (request == null) {
            scheduleStopIfIdle()
            return
        }
        val displayName = task?.displayName ?: request.modInfo.name
        activeTasks.incrementAndGet()
        updateNotification("$displayName: Starting")
        scope.launch {
            try {
                val result = NexusModManager.importNexusFile(
                    context = applicationContext,
                    appId = request.appId,
                    reference = request.reference,
                    modInfo = request.modInfo,
                    file = request.file,
                    onDetailedProgress = { progress ->
                        updateNotification("$displayName: ${progress.status}")
                        task?.progressSink?.invoke(progress)
                    },
                )
                task?.deferred?.complete(result)
            } catch (e: CancellationException) {
                task?.deferred?.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Nexus mod import failed")
                task?.deferred?.completeExceptionally(e)
            } finally {
                if (activeTasks.decrementAndGet() <= 0) {
                    scheduleStopIfIdle()
                }
            }
        }
    }

    private fun cancelDelayedStop() {
        mainHandler.removeCallbacks(delayedStop)
    }

    private fun scheduleStopIfIdle() {
        if (activeTasks.get() > 0 || pendingTasks.isNotEmpty() || resumeInProgress.get()) return
        mainHandler.removeCallbacks(delayedStop)
        mainHandler.postDelayed(delayedStop, STOP_GRACE_MS)
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun resumeInterruptedImports() {
        if (!resumeInProgress.compareAndSet(false, true)) {
            scheduleStopIfIdle()
            return
        }
        activeTasks.incrementAndGet()
        scope.launch {
            try {
                val dao = NexusModManager.dao(applicationContext)
                val interrupted = NexusModManager.resumableImportStatuses
                    .flatMap { status -> dao.getInstallsByStatus(status) }
                    .distinctBy { it.installId }
                if (interrupted.isEmpty()) return@launch
                interrupted.forEach { install ->
                    updateNotification("${install.modName}: Resuming")
                    try {
                        NexusModManager.importNexusFile(
                            context = applicationContext,
                            appId = install.appId,
                            reference = NexusModReference(
                                gameDomain = install.nexusGameDomain,
                                modId = install.nexusModId,
                                fileId = install.nexusFileId,
                            ),
                            modInfo = NexusModInfo(
                                modId = install.nexusModId,
                                name = install.modName,
                                summary = install.metadataSummary(),
                                version = install.version,
                            ),
                            file = NexusModFile(
                                fileId = install.nexusFileId,
                                name = install.fileName,
                                version = install.version,
                                fileName = install.fileName,
                                sizeBytes = install.sizeBytes,
                                uploadedTimestamp = 0L,
                                isPrimary = true,
                            ),
                            onDetailedProgress = { progress ->
                                updateNotification("${install.modName}: ${progress.status}")
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (error: Exception) {
                        Timber.w(error, "Failed to resume Nexus import ${install.installId}")
                    }
                }
            } finally {
                resumeInProgress.set(false)
                if (activeTasks.decrementAndGet() <= 0) {
                    scheduleStopIfIdle()
                }
            }
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "pluvia://home".toUri(),
            this,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val smallIconRes = if (PrefManager.useAltNotificationIcon) {
            R.drawable.ic_notification_alt
        } else {
            R.drawable.ic_notification
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(smallIconRes)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nexus Mod Imports",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Nexus mod downloads and unpacking alive in the background"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_RUN_IMPORT = "app.gamenative.service.NexusModImportService.RUN_IMPORT"
        private const val ACTION_RESUME_IMPORTS = "app.gamenative.service.NexusModImportService.RESUME_IMPORTS"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_APP_ID = "app_id"
        private const val EXTRA_GAME_DOMAIN = "game_domain"
        private const val EXTRA_MOD_ID = "mod_id"
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_MOD_NAME = "mod_name"
        private const val EXTRA_MOD_SUMMARY = "mod_summary"
        private const val EXTRA_MOD_VERSION = "mod_version"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_FILE_DISPLAY_NAME = "file_display_name"
        private const val EXTRA_FILE_VERSION = "file_version"
        private const val EXTRA_FILE_SIZE_BYTES = "file_size_bytes"
        private const val CHANNEL_ID = "nexus_mod_imports"
        private const val NOTIFICATION_ID = 42
        private const val STOP_GRACE_MS = 15_000L

        private val pendingTasks = ConcurrentHashMap<String, ImportTask>()
        private val activeTasks = AtomicInteger(0)
        private val resumeInProgress = AtomicBoolean(false)

        fun enqueueImport(
            context: Context,
            appId: String,
            reference: NexusModReference,
            modInfo: NexusModInfo,
            file: NexusModFile,
            displayName: String,
            onProgress: (ModImportProgress) -> Unit = {},
        ): Deferred<ModInstall> {
            val appContext = context.applicationContext
            val taskId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<ModInstall>()
            val request = NexusImportRequest(
                appId = appId,
                reference = reference.copy(fileId = reference.fileId ?: file.fileId),
                modInfo = modInfo,
                file = file,
            )
            pendingTasks[taskId] = ImportTask(
                displayName = displayName,
                request = request,
                progressSink = onProgress,
                deferred = deferred,
            )
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, NexusModImportService::class.java).apply {
                        action = ACTION_RUN_IMPORT
                        putExtra(EXTRA_TASK_ID, taskId)
                        putImportRequest(this, request)
                    },
                )
            } catch (e: Exception) {
                pendingTasks.remove(taskId)
                deferred.completeExceptionally(e)
            }
            return deferred
        }

        fun resumeInterruptedImports(context: Context) {
            if (PrefManager.nexusApiKey.isBlank()) return

            val appContext = context.applicationContext
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, NexusModImportService::class.java).apply {
                        action = ACTION_RESUME_IMPORTS
                    },
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to resume Nexus imports")
            }
        }

        private fun putImportRequest(intent: Intent, request: NexusImportRequest) {
            intent.putExtra(EXTRA_APP_ID, request.appId)
            intent.putExtra(EXTRA_GAME_DOMAIN, request.reference.gameDomain)
            intent.putExtra(EXTRA_MOD_ID, request.reference.modId)
            intent.putExtra(EXTRA_FILE_ID, request.file.fileId)
            intent.putExtra(EXTRA_MOD_NAME, request.modInfo.name)
            intent.putExtra(EXTRA_MOD_SUMMARY, request.modInfo.summary)
            intent.putExtra(EXTRA_MOD_VERSION, request.modInfo.version)
            intent.putExtra(EXTRA_FILE_NAME, request.file.fileName)
            intent.putExtra(EXTRA_FILE_DISPLAY_NAME, request.file.name)
            intent.putExtra(EXTRA_FILE_VERSION, request.file.version)
            intent.putExtra(EXTRA_FILE_SIZE_BYTES, request.file.sizeBytes)
        }
    }

    private data class ImportTask(
        val displayName: String,
        val request: NexusImportRequest,
        val progressSink: (ModImportProgress) -> Unit,
        val deferred: CompletableDeferred<ModInstall>,
    )

    private data class NexusImportRequest(
        val appId: String,
        val reference: NexusModReference,
        val modInfo: NexusModInfo,
        val file: NexusModFile,
    )

    private fun Intent.toImportRequest(): NexusImportRequest? {
        val appId = getStringExtra(EXTRA_APP_ID)?.takeIf { it.isNotBlank() } ?: return null
        val gameDomain = getStringExtra(EXTRA_GAME_DOMAIN)?.takeIf { it.isNotBlank() } ?: return null
        val modId = getLongExtra(EXTRA_MOD_ID, 0L).takeIf { it > 0L } ?: return null
        val fileId = getLongExtra(EXTRA_FILE_ID, 0L).takeIf { it > 0L } ?: return null
        val fileName = getStringExtra(EXTRA_FILE_NAME).orEmpty()
        return NexusImportRequest(
            appId = appId,
            reference = NexusModReference(gameDomain, modId, fileId),
            modInfo = NexusModInfo(
                modId = modId,
                name = getStringExtra(EXTRA_MOD_NAME).orEmpty().ifBlank { "Nexus mod $modId" },
                summary = getStringExtra(EXTRA_MOD_SUMMARY).orEmpty(),
                version = getStringExtra(EXTRA_MOD_VERSION).orEmpty(),
            ),
            file = NexusModFile(
                fileId = fileId,
                name = getStringExtra(EXTRA_FILE_DISPLAY_NAME).orEmpty().ifBlank { fileName },
                version = getStringExtra(EXTRA_FILE_VERSION).orEmpty(),
                fileName = fileName.ifBlank { "mod_$fileId" },
                sizeBytes = getLongExtra(EXTRA_FILE_SIZE_BYTES, 0L),
                uploadedTimestamp = 0L,
                isPrimary = true,
            ),
        )
    }
}

private fun ModInstall.metadataSummary(): String =
    runCatching { JSONObject(metadataJson).optString("summary") }.getOrDefault("")
