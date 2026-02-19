package app.gamenative.utils.cloudSync

import android.content.Context
import app.gamenative.R
import app.gamenative.data.PostSyncInfo
import app.gamenative.enums.PathType
import app.gamenative.enums.SyncResult
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.enums.DialogType
import app.gamenative.utils.ContainerUtils
import app.gamenative.data.GameSource
import app.gamenative.utils.CloudSaveCallbacks
import com.winlator.container.Container
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientObjects.ECloudPendingRemoteOperation
import java.util.Date
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import timber.log.Timber

const val LOADING_PROGRESS_UNKNOWN: Float = -1f

/** Steam cloud save sync before launch. Resolves all sync outcomes (Proceed / ShowDialog / Retry) inline. */
internal object SteamCloudSavePlatform : CloudSavePlatform {
    override fun appliesTo(container: Container) =
        ContainerUtils.extractGameSourceFromContainerId(container.id) == GameSource.STEAM

    override fun getLoadingMessage(context: Context, container: Container) =
        context.getString(R.string.main_syncing_cloud_saves)

    override suspend fun sync(
        context: Context,
        container: Container,
        params: CloudSyncParams,
        callbacks: CloudSaveCallbacks,
    ): CloudSyncOutcome = coroutineScope {
        val prefixToPath: (String) -> String = { prefix ->
            PathType.from(prefix).toAbsPath(context, params.gameId, SteamService.userSteamId!!.accountID)
        }
        callbacks.setLoadingMessage(getLoadingMessage(context, container))
        callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
        val postSyncInfo = SteamService.beginLaunchApp(
            appId = params.gameId,
            prefixToPath = prefixToPath,
            ignorePendingOperations = params.ignorePendingOperations,
            preferredSave = params.preferredSave,
            parentScope = params.scope,
            isOffline = params.isOffline,
            onProgress = { message, progress ->
                callbacks.setLoadingMessage(message)
                callbacks.setLoadingProgress(if (progress < 0) -1f else progress)
            },
        ).await()
        resolveResult(postSyncInfo, context, params.appId, params.useTemporaryOverride, params.retryCount)
    }

    private suspend fun resolveResult(
        postSyncInfo: PostSyncInfo,
        context: Context,
        appId: String,
        useTemporaryOverride: Boolean,
        retryCount: Int,
    ): CloudSyncOutcome = when (postSyncInfo.syncResult) {
        SyncResult.Conflict -> CloudSyncOutcome.ShowDialog(
            MessageDialogState(
                visible = true,
                type = DialogType.SYNC_CONFLICT,
                title = context.getString(R.string.main_save_conflict_title),
                message = context.getString(
                    R.string.main_save_conflict_message,
                    Date(postSyncInfo.localTimestamp).toString(),
                    Date(postSyncInfo.remoteTimestamp).toString(),
                ),
                dismissBtnText = context.getString(R.string.main_keep_local),
                confirmBtnText = context.getString(R.string.main_keep_remote),
            ),
        )

        SyncResult.InProgress -> {
            if (useTemporaryOverride && retryCount < 5) {
                Timber.i("Sync in progress for intent launch, retrying in 2 seconds... (attempt ${retryCount + 1}/5)")
                delay(2000)
                CloudSyncOutcome.Retry
            } else {
                CloudSyncOutcome.ShowDialog(
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
        -> CloudSyncOutcome.ShowDialog(
            MessageDialogState(
                visible = true,
                type = DialogType.SYNC_FAIL,
                title = context.getString(R.string.sync_error_title),
                message = context.getString(R.string.main_sync_failed, postSyncInfo.syncResult.toString()),
                dismissBtnText = context.getString(R.string.ok),
            ),
        )

        SyncResult.PendingOperations -> {
            Timber.i(
                "Pending remote operations:${
                    postSyncInfo.pendingRemoteOperations.map { pro ->
                        "\n\tmachineName: ${pro.machineName}" +
                            "\n\ttimestamp: ${Date(pro.timeLastUpdated * 1000L)}" +
                            "\n\toperation: ${pro.operation}"
                    }.joinToString("\n")
                }",
            )
            val state = if (postSyncInfo.pendingRemoteOperations.size == 1) {
                val pro = postSyncInfo.pendingRemoteOperations.first()
                val gameName = SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(appId))?.name ?: ""
                val dateStr = Date(pro.timeLastUpdated * 1000L).toString()
                when (pro.operation) {
                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadInProgress ->
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
                        )
                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadPending ->
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
                        )
                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionActive ->
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
                        )
                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionSuspended ->
                        MessageDialogState(
                            visible = true,
                            type = DialogType.APP_SESSION_SUSPENDED,
                            title = context.getString(R.string.sync_error_title),
                            message = context.getString(R.string.main_app_session_suspended),
                            dismissBtnText = context.getString(R.string.ok),
                        )
                    ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationNone ->
                        MessageDialogState(
                            visible = true,
                            type = DialogType.PENDING_OPERATION_NONE,
                            title = context.getString(R.string.sync_error_title),
                            message = context.getString(R.string.main_pending_operation_none),
                            dismissBtnText = context.getString(R.string.ok),
                        )
                }
            } else {
                MessageDialogState(
                    visible = true,
                    type = DialogType.MULTIPLE_PENDING_OPERATIONS,
                    title = context.getString(R.string.sync_error_title),
                    message = context.getString(R.string.main_multiple_pending_operations),
                    dismissBtnText = context.getString(R.string.ok),
                )
            }
            CloudSyncOutcome.ShowDialog(state)
        }

        SyncResult.UpToDate,
        SyncResult.Success,
        -> CloudSyncOutcome.Proceed
    }

    override suspend fun upload(
        context: Context,
        appId: String,
        gameId: Int,
        isOffline: Boolean,
        prefixToPath: (String) -> String,
    ) {
        SteamService.closeApp(gameId, isOffline, prefixToPath).await()
    }
}
