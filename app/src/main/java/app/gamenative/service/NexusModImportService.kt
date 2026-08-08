package app.gamenative.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import app.gamenative.data.ModInstallSource
import app.gamenative.data.ModInstallStatus
import app.gamenative.db.dao.ModDao
import app.gamenative.mods.LocalModImportRequest
import app.gamenative.mods.LocalModImporter
import app.gamenative.mods.LocalModSourceSelection
import app.gamenative.mods.LocalModSourceType
import app.gamenative.mods.ModDownloadRegistry
import app.gamenative.mods.ModImportProgress
import app.gamenative.mods.NexusApiClient
import app.gamenative.mods.NexusDownloadAuthorization
import app.gamenative.mods.NexusImportState
import app.gamenative.mods.NexusIntegrationStatus
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NexusModImportService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deferredStartIntents = ArrayDeque<Intent>()
    @Volatile
    private var destroyed = false
    private val delayedStop = Runnable {
        if (isIdle()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(serviceInstanceLock) {
            currentService = this
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mainHandler.removeCallbacks(delayedStop)
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.mod_import_preparing)))
        if (
            !NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE &&
            intent?.action == ACTION_RUN_IMPORT
        ) {
            scope.launch {
                try {
                    pauseInterruptedImports(applicationContext)
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }
        when {
            intent?.action == ACTION_RUN_IMPORT || intent?.action == ACTION_RUN_LOCAL_IMPORT ->
                dispatchOrDefer(intent)
            intent == null || intent.action == ACTION_RESUME_IMPORTS ->
                requestResumeInterruptedImports()
            else -> scheduleStopIfIdle()
        }
        return if (
            intent?.action == ACTION_RUN_LOCAL_IMPORT ||
            NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE
        ) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        synchronized(serviceInstanceLock) {
            if (currentService === this) currentService = null
        }
        mainHandler.removeCallbacks(delayedStop)
        failDeferredStarts(CancellationException("Mod import service stopped"))
        scope.cancel()
        super.onDestroy()
    }

    private fun startQueuedTask(taskId: String?, intent: Intent) {
        val task = taskId?.let { pendingTasks.remove(it) }
        val request = task?.request ?: decodeImportRequest(intent)
        if (request == null) {
            resumeOrStopIfIdle()
            return
        }
        val displayName = task?.displayName ?: request.modInfo.name
        launchImport(displayName, task?.deferred, task?.progressSink, "Nexus mod import failed") { progress ->
            NexusModManager.importNexusFile(
                context = applicationContext,
                appId = request.appId,
                reference = request.reference,
                modInfo = request.modInfo,
                file = request.file,
                isPremiumAccount = request.isPremiumAccount,
                onDetailedProgress = progress,
            )
        }
    }

    private fun startQueuedLocalTask(taskId: String?, intent: Intent) {
        val task = taskId?.let { pendingLocalTasks.remove(it) }
        val request = task?.request ?: decodeLocalImportRequest(intent)
        if (request == null) {
            resumeOrStopIfIdle()
            return
        }
        val sourceUris = task?.sourceUris ?: decodeLocalSourceUris(intent)
        launchImport(
            displayName = request.modName,
            deferred = task?.deferred,
            progressSink = task?.progressSink,
            failureLog = "Local mod import failed",
        ) { progress ->
            LocalModImporter.importMod(applicationContext, request, sourceUris, progress)
        }
    }

    private fun launchImport(
        displayName: String,
        deferred: CompletableDeferred<ModInstall>?,
        progressSink: ((ModImportProgress) -> Unit)?,
        failureLog: String,
        importer: suspend ((ModImportProgress) -> Unit) -> ModInstall,
    ) {
        activeTasks.incrementAndGet()
        updateNotification("$displayName: ${getString(R.string.nexus_queue_starting)}")
        scope.launch {
            try {
                val result = importer { progress ->
                    updateNotification("$displayName: ${localizedProgressStatus(progress.status)}")
                    progressSink?.invoke(progress)
                }
                deferred?.complete(result)
            } catch (e: CancellationException) {
                deferred?.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                Timber.w(e, failureLog)
                deferred?.completeExceptionally(e)
            }
        }.invokeOnCompletion { cause ->
            if (cause != null && deferred?.isCompleted == false) {
                deferred.completeExceptionally(cause)
            }
            finishActiveTask()
        }
    }

    private fun dispatchOrDefer(intent: Intent) {
        if (destroyed) {
            failQueuedIntent(intent, CancellationException("Mod import service stopped"))
            return
        }
        if (resumeInProgress.get()) {
            deferredStartIntents.addLast(Intent(intent))
            return
        }
        when (intent.action) {
            ACTION_RUN_IMPORT -> startQueuedTask(intent.getStringExtra(EXTRA_TASK_ID), intent)
            ACTION_RUN_LOCAL_IMPORT -> startQueuedLocalTask(intent.getStringExtra(EXTRA_TASK_ID), intent)
        }
    }

    private fun drainDeferredStarts() {
        while (!destroyed && !resumeInProgress.get() && deferredStartIntents.isNotEmpty()) {
            dispatchOrDefer(deferredStartIntents.removeFirst())
        }
    }

    private fun failDeferredStarts(error: CancellationException) {
        while (deferredStartIntents.isNotEmpty()) {
            failQueuedIntent(deferredStartIntents.removeFirst(), error)
        }
    }

    private fun failQueuedIntent(intent: Intent, error: CancellationException) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        when (intent.action) {
            ACTION_RUN_IMPORT -> pendingTasks.remove(taskId)?.deferred?.completeExceptionally(error)
            ACTION_RUN_LOCAL_IMPORT ->
                pendingLocalTasks.remove(taskId)?.deferred?.completeExceptionally(error)
        }
    }

    private fun hasActiveImports(): Boolean =
        activeTasks.get() > 0 || pendingTasks.isNotEmpty() || pendingLocalTasks.isNotEmpty()

    private fun isIdle(): Boolean =
        !hasActiveImports() &&
            deferredStartIntents.isEmpty() &&
            !resumeInProgress.get() &&
            !resumeRequested.get()

    private fun scheduleStopIfIdle() {
        if (!isIdle()) return
        mainHandler.removeCallbacks(delayedStop)
        mainHandler.postDelayed(delayedStop, STOP_GRACE_MS)
    }

    private fun requestResumeInterruptedImports() {
        resumeRequested.set(true)
        resumeInterruptedImports()
    }

    private fun finishActiveTask() {
        if (activeTasks.decrementAndGet() <= 0) {
            val service = synchronized(serviceInstanceLock) { currentService }
            service?.mainHandler?.post { service.resumeOrStopIfIdle() }
        }
    }

    private fun resumeOrStopIfIdle() {
        if (destroyed) return
        drainDeferredStarts()
        if (resumeRequested.get()) resumeInterruptedImports() else scheduleStopIfIdle()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun localizedProgressStatus(status: String): String = when (status) {
        "Starting" -> getString(R.string.nexus_queue_starting)
        "Copying" -> getString(R.string.local_mod_import_status_copying)
        "Downloading" -> getString(R.string.nexus_import_status_downloading)
        "Unpacking" -> getString(R.string.nexus_import_status_unpacking)
        else -> status
    }

    private fun resumeInterruptedImports() {
        if (hasActiveImports()) return
        if (!resumeInProgress.compareAndSet(false, true)) {
            return
        }
        resumeRequested.set(false)
        activeTasks.incrementAndGet()
        scope.launch {
            val dao = NexusModManager.dao(applicationContext)
            val onlineAccessAvailable = NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE
            val interrupted = queryResumableImports(dao)
            if (interrupted.isEmpty()) return@launch
            val (localInterrupted, nexusCandidates) = interrupted.partition {
                ModInstallSource.isLocal(it.source)
            }
            localInterrupted.forEach { snapshot ->
                val install = currentResumableLocalInstall(dao, snapshot.installId)
                    ?: return@forEach
                if (!NexusModManager.hasCompletePendingLocalContent(applicationContext, install)) {
                    val previousContentAvailable = try {
                        NexusModManager.discardIncompletePendingLocalContent(
                            context = applicationContext,
                            install = install,
                            failureMessage = getString(R.string.local_mod_import_failed),
                        )
                    } catch (error: Exception) {
                        Timber.w(
                            error,
                            "Failed to clean interrupted local import ${install.installId}",
                        )
                        false
                    }
                    val current = currentResumableLocalInstall(dao, install.installId)
                        ?: return@forEach
                    dao.updateInstall(
                        terminalLocalResumeFailure(
                            current,
                            getString(R.string.local_mod_reselect),
                            preserveCompletedTransfer = false,
                            restorePreviousInstall = previousContentAvailable,
                        ),
                    )
                    return@forEach
                }
                updateNotification("${install.modName}: ${getString(R.string.nexus_resume)}")
                try {
                    val request = install.toLocalImportRequest()
                        ?: throw IllegalStateException("Unknown local mod source: ${install.source}")
                    LocalModImporter.importMod(
                        context = applicationContext,
                        request = request,
                        sourceUris = emptyList(),
                        onDetailedProgress = { progress ->
                            updateNotification(
                                "${install.modName}: ${localizedProgressStatus(progress.status)}",
                            )
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    Timber.w(error, "Failed to resume local mod import ${install.installId}")
                    recordLocalResumeFailureIfInterrupted(dao, install.installId, error)
                }
            }

            val nexusInterrupted = nexusCandidates
                .filter { install ->
                    !onlineAccessAvailable || !NexusImportState.isWaitingForWebsiteAuthorization(install)
                }
            val (completeArchives, downloadsNeedingLinks) = if (!onlineAccessAvailable) {
                pauseDownloadsNeedingOnlineAccess(
                    applicationContext,
                    dao,
                    nexusInterrupted,
                ) to emptyList()
            } else {
                partitionByCompleteArchive(applicationContext, nexusInterrupted)
            }
            val nexusUser = if (downloadsNeedingLinks.isNotEmpty()) {
                try {
                    NexusApiClient().getCurrentUser()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not validate Nexus account before resuming imports")
                    downloadsNeedingLinks.forEach { install ->
                        dao.updateInstallStatus(install.installId, ModInstallStatus.PAUSED.name)
                    }
                    null
                }
            } else {
                null
            }
            if (nexusUser?.isPremium == false) {
                val message = getString(R.string.nexus_resume_requires_website_authorization)
                downloadsNeedingLinks.forEach { install ->
                    dao.upsertInstall(NexusImportState.pauseForWebsiteAuthorization(install, message))
                }
                Timber.i("Paused free-account Nexus imports until the user authorizes each file on the website")
            }
            val resumable = completeArchives + if (
                onlineAccessAvailable && nexusUser?.isPremium == true
            ) {
                downloadsNeedingLinks
            } else {
                emptyList()
            }
            resumable.forEach { install ->
                val gameDomain = install.nexusGameDomain
                val modId = install.nexusModId
                val fileId = install.nexusFileId
                if (gameDomain.isNullOrBlank() || modId == null || fileId == null) {
                    dao.upsertInstall(
                        install.copy(
                            status = ModInstallStatus.ERROR.name,
                            updatedAt = System.currentTimeMillis(),
                            metadataJson = NexusImportState.errorMetadata(
                                summary = install.metadataSummary(),
                                error = getString(R.string.nexus_invalid_source_metadata),
                            ),
                        ),
                    )
                    return@forEach
                }
                updateNotification("${install.modName}: ${getString(R.string.nexus_resume)}")
                try {
                    NexusModManager.importNexusFile(
                        context = applicationContext,
                        appId = install.appId,
                        reference = NexusModReference(
                            gameDomain = gameDomain,
                            modId = modId,
                            fileId = fileId,
                        ),
                        modInfo = NexusModInfo(
                            modId = modId,
                            name = install.modName,
                            summary = install.metadataSummary(),
                            version = install.version,
                        ),
                        file = NexusModFile(
                            fileId = fileId,
                            name = install.fileName,
                            version = install.version,
                            fileName = install.fileName,
                            sizeBytes = install.sizeBytes,
                            uploadedTimestamp = 0L,
                            isPrimary = true,
                        ),
                        isPremiumAccount = nexusUser?.isPremium,
                        onDetailedProgress = { progress ->
                            updateNotification(
                                "${install.modName}: ${localizedProgressStatus(progress.status)}",
                            )
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    Timber.w(error, "Failed to resume Nexus import ${install.installId}")
                }
            }
        }.invokeOnCompletion {
            resumeInProgress.set(false)
            mainHandler.post {
                if (!destroyed) {
                    drainDeferredStarts()
                }
                finishActiveTask()
            }
        }
    }

    private suspend fun recordLocalResumeFailureIfInterrupted(
        dao: ModDao,
        installId: String,
        error: Exception,
    ) {
        val current = currentResumableLocalInstall(dao, installId) ?: return
        val requiresReselection =
            error.message == getString(R.string.local_mod_invalid_request) ||
                error.message == getString(R.string.local_mod_too_large)
        val previousContentAvailable = try {
            NexusModManager.restorePreviousLocalInstall(
                context = applicationContext,
                install = current,
                failureMessage = getString(R.string.local_mod_import_failed),
            ) != null
        } catch (restoreError: Exception) {
            Timber.w(restoreError, "Failed to restore previous local mod ${current.installId}")
            false
        }
        dao.updateInstall(
            terminalLocalResumeFailure(
                current,
                getString(R.string.local_mod_import_failed),
                preserveCompletedTransfer = !requiresReselection,
                restorePreviousInstall = previousContentAvailable,
            ),
        )
    }

    private suspend fun currentResumableLocalInstall(
        dao: ModDao,
        installId: String,
    ): ModInstall? = dao.getInstall(installId)?.takeIf { install ->
        ModInstallSource.isLocal(install.source) &&
            install.status in NexusImportState.resumableImportStatuses &&
            ModDownloadRegistry.get(installId) == null
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
            getString(R.string.mod_import_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.mod_import_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_RUN_IMPORT = "app.gamenative.service.NexusModImportService.RUN_IMPORT"
        private const val ACTION_RUN_LOCAL_IMPORT =
            "app.gamenative.service.NexusModImportService.RUN_LOCAL_IMPORT"
        private const val ACTION_RESUME_IMPORTS = "app.gamenative.service.NexusModImportService.RESUME_IMPORTS"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_INSTALL_ID = "install_id"
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
        private const val EXTRA_LOCAL_SOURCE_TYPE = "local_source_type"
        private const val EXTRA_DOWNLOAD_AUTHORIZATION_KEY = "download_authorization_key"
        private const val EXTRA_DOWNLOAD_AUTHORIZATION_EXPIRES = "download_authorization_expires"
        private const val EXTRA_DOWNLOAD_AUTHORIZATION_USER_ID = "download_authorization_user_id"
        private const val EXTRA_IS_PREMIUM_ACCOUNT = "is_premium_account"
        private const val CHANNEL_ID = "nexus_mod_imports"
        private const val NOTIFICATION_ID = 42
        private const val STOP_GRACE_MS = 15_000L

        private val pendingTasks = ConcurrentHashMap<String, ImportTask>()
        private val pendingLocalTasks = ConcurrentHashMap<String, LocalImportTask>()
        private val activeTasks = AtomicInteger(0)
        private val resumeInProgress = AtomicBoolean(false)
        private val resumeRequested = AtomicBoolean(false)
        private val serviceInstanceLock = Any()
        @Volatile
        private var currentService: NexusModImportService? = null

        fun enqueueImport(
            context: Context,
            appId: String,
            reference: NexusModReference,
            modInfo: NexusModInfo,
            file: NexusModFile,
            displayName: String,
            isPremiumAccount: Boolean? = null,
            onProgress: (ModImportProgress) -> Unit = {},
        ): Deferred<ModInstall> {
            if (!NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE) {
                return failedImport(
                    IllegalStateException(
                        context.getString(R.string.nexus_integration_temporarily_unavailable),
                    ),
                )
            }
            if (reference.fileId != null && reference.fileId != file.fileId) {
                return failedImport(
                    IllegalArgumentException(
                        "The Nexus authorization does not match the selected file",
                    ),
                )
            }
            val request = NexusImportRequest(
                appId = appId,
                reference = reference.copy(fileId = reference.fileId ?: file.fileId),
                modInfo = modInfo,
                file = file,
                isPremiumAccount = isPremiumAccount,
            )
            return enqueueServiceTask(
                context = context,
                action = ACTION_RUN_IMPORT,
                pending = pendingTasks,
                task = { deferred ->
                    ImportTask(displayName, request, onProgress, deferred)
                },
            ) {
                putImportRequest(this, request)
            }
        }

        fun enqueueLocalImport(
            context: Context,
            appId: String,
            source: LocalModSourceSelection,
            modName: String,
            version: String,
            installId: String = "local_${UUID.randomUUID()}",
            onProgress: (ModImportProgress) -> Unit = {},
        ): Deferred<ModInstall> {
            val request = LocalModImportRequest(
                installId = installId,
                appId = appId,
                sourceType = source.type,
                modName = modName.trim(),
                sourceName = source.displayName,
                version = version.trim(),
                sizeBytes = source.sizeBytes,
            )
            return enqueueLocalRequest(
                context = context,
                request = request,
                sourceUris = source.uris,
                requireSource = true,
                onProgress = onProgress,
            )
        }

        fun resumeLocalImport(
            context: Context,
            install: ModInstall,
            onProgress: (ModImportProgress) -> Unit = {},
        ): Deferred<ModInstall> {
            val request = install.toLocalImportRequest()
                ?: return failedImport(
                    IllegalArgumentException(context.getString(R.string.local_mod_invalid_request)),
                )
            return enqueueLocalRequest(
                context = context,
                request = request,
                sourceUris = emptyList(),
                requireSource = false,
                onProgress = onProgress,
            )
        }

        private fun enqueueLocalRequest(
            context: Context,
            request: LocalModImportRequest,
            sourceUris: List<Uri>,
            requireSource: Boolean,
            onProgress: (ModImportProgress) -> Unit,
        ): Deferred<ModInstall> {
            val appContext = context.applicationContext
            val normalizedSourceUris = try {
                LocalModImporter.validateImportRequest(
                    context = appContext,
                    request = request,
                    sourceUris = sourceUris,
                    requireSource = requireSource,
                )
            } catch (error: Exception) {
                return failedImport(error)
            }
            return enqueueServiceTask(
                context = context,
                action = ACTION_RUN_LOCAL_IMPORT,
                pending = pendingLocalTasks,
                task = { deferred ->
                    LocalImportTask(normalizedSourceUris, request, onProgress, deferred)
                },
            ) {
                grantLocalSourceUris(normalizedSourceUris)
                putLocalImportRequest(this, request)
            }
        }

        private fun failedImport(error: Exception): Deferred<ModInstall> =
            CompletableDeferred<ModInstall>().also { it.completeExceptionally(error) }

        private fun <T> enqueueServiceTask(
            context: Context,
            action: String,
            pending: ConcurrentHashMap<String, T>,
            task: (CompletableDeferred<ModInstall>) -> T,
            putRequest: Intent.() -> Unit,
        ): Deferred<ModInstall> {
            val appContext = context.applicationContext
            val taskId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<ModInstall>()
            pending[taskId] = task(deferred)
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, NexusModImportService::class.java).apply {
                        this.action = action
                        putExtra(EXTRA_TASK_ID, taskId)
                        putRequest()
                    },
                )
            } catch (error: Exception) {
                pending.remove(taskId)
                deferred.completeExceptionally(error)
            }
            return deferred
        }

        suspend fun resumeInterruptedImports(context: Context) {
            val appContext = context.applicationContext
            val hasInterruptedImports = withContext(Dispatchers.IO) {
                val dao = NexusModManager.dao(appContext)
                val interrupted = queryResumableImports(dao)
                val (localInterrupted, nexusInterrupted) = interrupted.partition {
                    ModInstallSource.isLocal(it.source)
                }
                if (!NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE) {
                    val completeNexusArchives = pauseDownloadsNeedingOnlineAccess(
                        appContext,
                        dao,
                        nexusInterrupted,
                    )
                    localInterrupted.isNotEmpty() || completeNexusArchives.isNotEmpty()
                } else {
                    localInterrupted.isNotEmpty() ||
                        nexusInterrupted.any { install ->
                            !NexusImportState.isWaitingForWebsiteAuthorization(install)
                        }
                }
            }
            if (!hasInterruptedImports) return
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, NexusModImportService::class.java).apply {
                        action = ACTION_RESUME_IMPORTS
                    },
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to resume mod imports")
            }
        }

        private suspend fun pauseInterruptedImports(context: Context) = withContext(Dispatchers.IO) {
            val dao = NexusModManager.dao(context)
            pauseDownloadsNeedingOnlineAccess(
                context,
                dao,
                queryResumableImports(dao).filter { it.source == ModInstallSource.NEXUS.name },
            )
        }

        private suspend fun queryResumableImports(dao: ModDao): List<ModInstall> =
            NexusModManager.resumableImportStatuses
                .flatMap { status -> dao.getInstallsByStatus(status) }
                .distinctBy { it.installId }
                .filter { ModDownloadRegistry.get(it.installId) == null }

        private fun partitionByCompleteArchive(
            context: Context,
            installs: List<ModInstall>,
        ): Pair<List<ModInstall>, List<ModInstall>> =
            installs.partition { NexusModManager.hasCompletePendingArchive(context, it) }

        private suspend fun pauseDownloadsNeedingOnlineAccess(
            context: Context,
            dao: ModDao,
            installs: List<ModInstall>,
        ): List<ModInstall> {
            val nexusInstalls = installs.filter { it.source == ModInstallSource.NEXUS.name }
            val (completeArchives, downloadsNeedingLinks) =
                partitionByCompleteArchive(context, nexusInstalls)
            pauseImportsWhileOnlineAccessUnavailable(context, dao, downloadsNeedingLinks)
            return completeArchives
        }

        private suspend fun pauseImportsWhileOnlineAccessUnavailable(
            context: Context,
            dao: ModDao,
            installs: List<ModInstall>,
        ) {
            val message = context.getString(R.string.nexus_integration_temporarily_unavailable)
            installs.forEach { install ->
                val paused = NexusImportState.pauseWhileOnlineAccessUnavailable(install, message)
                if (paused != install) dao.upsertInstall(paused)
            }
        }

        internal fun putImportRequest(intent: Intent, request: NexusImportRequest) {
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
            request.reference.downloadAuthorization?.let { authorization ->
                intent.putExtra(EXTRA_DOWNLOAD_AUTHORIZATION_KEY, authorization.key)
                intent.putExtra(EXTRA_DOWNLOAD_AUTHORIZATION_EXPIRES, authorization.expires)
                authorization.userId?.let { intent.putExtra(EXTRA_DOWNLOAD_AUTHORIZATION_USER_ID, it) }
            }
            request.isPremiumAccount?.let { intent.putExtra(EXTRA_IS_PREMIUM_ACCOUNT, it) }
        }

        private fun putLocalImportRequest(intent: Intent, request: LocalModImportRequest) {
            intent.putExtra(EXTRA_INSTALL_ID, request.installId)
            intent.putExtra(EXTRA_APP_ID, request.appId)
            intent.putExtra(EXTRA_LOCAL_SOURCE_TYPE, request.sourceType.name)
            intent.putExtra(EXTRA_MOD_NAME, request.modName)
            intent.putExtra(EXTRA_FILE_NAME, request.sourceName)
            intent.putExtra(EXTRA_FILE_VERSION, request.version)
            intent.putExtra(EXTRA_FILE_SIZE_BYTES, request.sizeBytes)
        }

        private fun decodeLocalImportRequest(intent: Intent): LocalModImportRequest? = with(intent) {
            val installId = getStringExtra(EXTRA_INSTALL_ID)
                ?.takeIf { it.startsWith("local_") }
                ?: return null
            val appId = getStringExtra(EXTRA_APP_ID)
                ?.takeIf(String::isNotBlank)
                ?: return null
            val sourceType = getStringExtra(EXTRA_LOCAL_SOURCE_TYPE)
                ?.let { runCatching { LocalModSourceType.valueOf(it) }.getOrNull() }
                ?: return null
            val modName = getStringExtra(EXTRA_MOD_NAME)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: return null
            val sourceName = getStringExtra(EXTRA_FILE_NAME)
                ?.takeIf(String::isNotBlank)
                ?: return null
            LocalModImportRequest(
                installId = installId,
                appId = appId,
                sourceType = sourceType,
                modName = modName,
                sourceName = sourceName,
                version = getStringExtra(EXTRA_FILE_VERSION).orEmpty(),
                sizeBytes = getLongExtra(EXTRA_FILE_SIZE_BYTES, 0L).coerceAtLeast(0L),
            )
        }

        internal fun Intent.grantLocalSourceUris(uris: List<Uri>) {
            if (uris.isEmpty()) return
            data = uris.first()
            // Folder selections are tree URIs, not streamable document URIs. Raw ClipData
            // preserves the grants without asking the provider to MIME-probe a tree URI.
            clipData = ClipData.newRawUri("Local mod content", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        internal fun decodeLocalSourceUris(intent: Intent): List<Uri> = buildList {
            intent.data?.let(::add)
            intent.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) {
                    clips.getItemAt(index).uri?.let(::add)
                }
            }
        }.distinct()

        internal fun decodeImportRequest(intent: Intent): NexusImportRequest? = with(intent) {
            val appId = getStringExtra(EXTRA_APP_ID)?.takeIf { it.isNotBlank() } ?: return null
            val gameDomain = getStringExtra(EXTRA_GAME_DOMAIN)?.takeIf { it.isNotBlank() } ?: return null
            val modId = getLongExtra(EXTRA_MOD_ID, 0L).takeIf { it > 0L } ?: return null
            val fileId = getLongExtra(EXTRA_FILE_ID, 0L).takeIf { it > 0L } ?: return null
            val fileName = getStringExtra(EXTRA_FILE_NAME).orEmpty()
            val downloadAuthorization = getStringExtra(EXTRA_DOWNLOAD_AUTHORIZATION_KEY)
                ?.takeIf { it.isNotBlank() && it.length <= 2048 }
                ?.let { key ->
                    val expires = getLongExtra(EXTRA_DOWNLOAD_AUTHORIZATION_EXPIRES, 0L).takeIf { it > 0L }
                        ?: return@let null
                    NexusDownloadAuthorization(
                        key = key,
                        expires = expires,
                        userId = getLongExtra(EXTRA_DOWNLOAD_AUTHORIZATION_USER_ID, 0L).takeIf { it > 0L },
                    )
                }
            NexusImportRequest(
                appId = appId,
                reference = NexusModReference(gameDomain, modId, fileId, downloadAuthorization),
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
                isPremiumAccount = if (hasExtra(EXTRA_IS_PREMIUM_ACCOUNT)) {
                    getBooleanExtra(EXTRA_IS_PREMIUM_ACCOUNT, false)
                } else {
                    null
                },
            )
        }
    }

    private data class ImportTask(
        val displayName: String,
        val request: NexusImportRequest,
        val progressSink: (ModImportProgress) -> Unit,
        val deferred: CompletableDeferred<ModInstall>,
    )

    private data class LocalImportTask(
        val sourceUris: List<Uri>,
        val request: LocalModImportRequest,
        val progressSink: (ModImportProgress) -> Unit,
        val deferred: CompletableDeferred<ModInstall>,
    )

    internal data class NexusImportRequest(
        val appId: String,
        val reference: NexusModReference,
        val modInfo: NexusModInfo,
        val file: NexusModFile,
        val isPremiumAccount: Boolean? = null,
    )

}

private fun ModInstall.metadataSummary(): String =
    runCatching { JSONObject(metadataJson).optString("summary") }.getOrDefault("")

private fun ModInstall.toLocalImportRequest(): LocalModImportRequest? {
    val sourceType = LocalModSourceType.fromInstallSource(source) ?: return null
    return LocalModImportRequest(
        installId = installId,
        appId = appId,
        sourceType = sourceType,
        modName = modName,
        sourceName = fileName,
        version = version,
        sizeBytes = sizeBytes,
    )
}

internal fun terminalLocalResumeFailure(
    install: ModInstall,
    message: String,
    preserveCompletedTransfer: Boolean,
    restorePreviousInstall: Boolean,
): ModInstall {
    val summary = install.metadataSummary()
    val previousInstall = NexusImportState.restorablePreviousInstall(install)
    if (!restorePreviousInstall && previousInstall != null) {
        return install.copy(
            status = ModInstallStatus.ERROR.name,
            updatedAt = System.currentTimeMillis(),
            metadataJson = NexusImportState.errorMetadata(
                summary,
                message,
                previousInstall,
            ),
        )
    }
    val terminal = NexusImportState.terminalInstall(
        importing = install,
        summary = summary,
        status = ModInstallStatus.ERROR,
        message = message,
        previousInstall = previousInstall.takeIf { restorePreviousInstall },
    )
    return if (
        terminal.status == ModInstallStatus.ERROR.name &&
        !preserveCompletedTransfer
    ) {
        terminal.copy(metadataJson = NexusImportState.errorMetadata(summary, message))
    } else {
        terminal
    }
}
