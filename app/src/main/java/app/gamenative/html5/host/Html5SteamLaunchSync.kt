package app.gamenative.html5.host

import android.content.Context
import app.gamenative.R
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.preLaunchApp
import app.gamenative.utils.ContainerUtils
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientObjects.ECloudPendingRemoteOperation
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import timber.log.Timber

// html5 copy of preLaunchApp's Steam cloud-sync result handling, kept separate so the wine path stays
// textually identical to master. differs only in chromiumProfileSync; keep the dialogs in step with it.
internal suspend fun runHtml5SteamCloudSync(
    context: Context,
    appId: String,
    gameId: Int,
    prefixToPath: (String) -> String,
    preferredSave: SaveLocation,
    eaPreferredSave: SaveLocation,
    rockstarPreferredSave: SaveLocation,
    ignorePendingOperations: Boolean,
    isOffline: Boolean,
    parentScope: CoroutineScope,
    setLoadingDialogVisible: (Boolean) -> Unit,
    setLoadingProgress: (Float) -> Unit,
    setLoadingMessage: (String) -> Unit,
    setMessageDialogState: (MessageDialogState) -> Unit,
    onSuccess: (Context, String) -> Unit,
    useTemporaryOverride: Boolean,
    retryCount: Int,
    bootToContainer: Boolean,
) {
    setLoadingMessage("Syncing cloud saves")
    setLoadingProgress(-1f)
    val postSyncInfo = SteamService.beginLaunchApp(
        appId = gameId,
        prefixToPath = prefixToPath,
        ignorePendingOperations = ignorePendingOperations,
        preferredSave = preferredSave,
        parentScope = parentScope,
        isOffline = isOffline,
        onProgress = { message, progress ->
            setLoadingMessage(message)
            setLoadingProgress(if (progress < 0) -1f else progress)
        },
        // leveldb churns files on compaction; without delete propagation cloud keeps a broken mix.
        chromiumProfileSync = true,
    ).await()

    setLoadingDialogVisible(false)

    when (postSyncInfo.syncResult) {
        SyncResult.Conflict -> {
            val localDate = Date(postSyncInfo.localTimestamp).toString()
            val remoteDate = Date(postSyncInfo.remoteTimestamp).toString()
            val (conflictTitle, conflictMessage) = postSyncInfo.conflictUfsVersion
                ?.let { v ->
                    val titleId = context.resources.getIdentifier(
                        "main_save_conflict_upgrade_v${v}_title", "string", context.packageName,
                    )
                    val msgId = context.resources.getIdentifier(
                        "main_save_conflict_upgrade_v${v}_message", "string", context.packageName,
                    )
                    if (titleId != 0 && msgId != 0) {
                        context.getString(titleId) to context.getString(msgId, localDate, remoteDate)
                    } else {
                        null
                    }
                }
                ?: run {
                    context.getString(R.string.main_save_conflict_title) to
                        context.getString(R.string.main_save_conflict_message, localDate, remoteDate)
                }
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_CONFLICT,
                    title = conflictTitle,
                    message = conflictMessage,
                    dismissBtnText = context.getString(R.string.main_keep_local),
                    confirmBtnText = context.getString(R.string.main_keep_remote),
                ),
            )
        }

        SyncResult.InProgress -> {
            if (useTemporaryOverride && retryCount < 5) {
                // For intent launches, retry after a short delay (max 5 retries = ~10 seconds)
                Timber.i("Sync in progress for intent launch, retrying in 2 seconds... (attempt ${retryCount + 1}/5)")
                delay(2000)
                preLaunchApp(
                    context = context,
                    appId = appId,
                    ignorePendingOperations = ignorePendingOperations,
                    preferredSave = preferredSave,
                    eaPreferredSave = eaPreferredSave,
                    rockstarPreferredSave = rockstarPreferredSave,
                    useTemporaryOverride = useTemporaryOverride,
                    setLoadingDialogVisible = setLoadingDialogVisible,
                    setLoadingProgress = setLoadingProgress,
                    setLoadingMessage = setLoadingMessage,
                    setMessageDialogState = setMessageDialogState,
                    onSuccess = onSuccess,
                    retryCount = retryCount + 1,
                    bootToContainer = bootToContainer,
                )
            } else {
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_IN_PROGRESS,
                        title = context.getString(R.string.sync_error_title),
                        message = context.getString(R.string.main_sync_in_progress_launch_anyway_message),
                        confirmBtnText = context.getString(R.string.main_launch_anyway),
                        dismissBtnText = context.getString(R.string.main_wait),
                    ),
                )
            }
        }

        SyncResult.UnknownFail,
        SyncResult.DownloadFail,
        SyncResult.UpdateFail,
        -> {
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.sync_error_title),
                    message = context.getString(R.string.main_sync_failed, postSyncInfo.syncResult.toString()),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
        }

        SyncResult.PendingOperations -> {
            Timber.i(
                "Pending remote operations:${
                    postSyncInfo.pendingRemoteOperations.joinToString("\n") { pro ->
                        "\n\tmachineName: ${pro.machineName}" +
                            "\n\ttimestamp: ${Date(pro.timeLastUpdated * 1000L)}" +
                            "\n\toperation: ${pro.operation}"
                    }
                }",
            )
            if (postSyncInfo.pendingRemoteOperations.size == 1) {
                val pro = postSyncInfo.pendingRemoteOperations.first()
                val gameName = SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(appId))?.name ?: ""
                val dateStr = Date(pro.timeLastUpdated * 1000L).toString()
                when (pro.operation) {
                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadInProgress -> {
                        // maybe this should instead wait for the upload to finish and then
                        // launch the app
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.PENDING_UPLOAD_IN_PROGRESS,
                                title = context.getString(R.string.main_upload_in_progress_title),
                                message = context.getString(
                                    R.string.main_upload_in_progress_message,
                                    gameName,
                                    pro.machineName,
                                    dateStr,
                                ),
                                dismissBtnText = context.getString(R.string.ok),
                            ),
                        )
                    }

                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadPending -> {
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.PENDING_UPLOAD,
                                title = context.getString(R.string.main_pending_upload_title),
                                message = context.getString(
                                    R.string.main_pending_upload_message,
                                    gameName,
                                    pro.machineName,
                                    dateStr,
                                ),
                                confirmBtnText = context.getString(R.string.main_play_anyway),
                                dismissBtnText = context.getString(R.string.cancel),
                            ),
                        )
                    }

                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionActive -> {
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.APP_SESSION_ACTIVE,
                                title = context.getString(R.string.main_app_running_title),
                                message = context.getString(
                                    R.string.main_app_running_other_device,
                                    pro.machineName,
                                    gameName,
                                    dateStr,
                                ),
                                confirmBtnText = context.getString(R.string.main_play_anyway),
                                dismissBtnText = context.getString(R.string.cancel),
                            ),
                        )
                    }

                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionSuspended -> {
                        // I don't know what this means, yet
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.APP_SESSION_SUSPENDED,
                                title = context.getString(R.string.sync_error_title),
                                message = context.getString(R.string.main_app_session_suspended),
                                dismissBtnText = context.getString(R.string.ok),
                            ),
                        )
                    }

                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationNone -> {
                        // why are we here
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.PENDING_OPERATION_NONE,
                                title = context.getString(R.string.sync_error_title),
                                message = context.getString(R.string.main_pending_operation_none),
                                dismissBtnText = context.getString(R.string.ok),
                            ),
                        )
                    }
                }
            } else {
                val uploadInProgress = postSyncInfo.pendingRemoteOperations.firstOrNull {
                    it.operation == ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadInProgress
                }
                if (uploadInProgress != null) {
                    val gameName = SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(appId))?.name ?: ""
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.PENDING_UPLOAD_IN_PROGRESS,
                            title = context.getString(R.string.main_upload_in_progress_title),
                            message = context.getString(
                                R.string.main_upload_in_progress_message,
                                gameName,
                                uploadInProgress.machineName,
                                Date(uploadInProgress.timeLastUpdated * 1000L).toString(),
                            ),
                            dismissBtnText = context.getString(R.string.ok),
                        ),
                    )
                } else {
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.MULTIPLE_PENDING_OPERATIONS,
                            title = context.getString(R.string.sync_error_title),
                            message = context.getString(R.string.main_multiple_pending_operations_message),
                            confirmBtnText = context.getString(R.string.main_play_anyway),
                            dismissBtnText = context.getString(R.string.cancel),
                        ),
                    )
                }
            }
        }

        SyncResult.UpToDate,
        SyncResult.Success,
        -> onSuccess(context, appId)
    }
}
