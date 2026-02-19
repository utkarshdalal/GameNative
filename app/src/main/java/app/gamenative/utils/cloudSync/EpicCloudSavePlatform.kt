package app.gamenative.utils.cloudSync

import android.content.Context
import app.gamenative.R
import app.gamenative.service.epic.EpicService
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.enums.DialogType
import app.gamenative.utils.ContainerUtils
import app.gamenative.data.GameSource
import app.gamenative.utils.CloudSaveCallbacks
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Epic cloud save sync and launch token cleanup before launch. */
internal object EpicCloudSavePlatform : CloudSavePlatform {
    override fun appliesTo(container: Container) =
        ContainerUtils.extractGameSourceFromContainerId(container.id) == GameSource.EPIC

    override fun getLoadingMessage(context: Context, container: Container) =
        context.getString(R.string.main_syncing_cloud_saves)

    override suspend fun sync(
        context: Context,
        container: Container,
        params: CloudSyncParams,
        callbacks: CloudSaveCallbacks,
    ): CloudSyncOutcome {
        Timber.tag("Epic").i("[Cloud Saves] Epic Game detected for appId=${params.appId} gameId=${params.gameId} — syncing cloud saves before launch")
        try {
            app.gamenative.service.epic.EpicCloudSavesManager.syncCloudSaves(
                context = context,
                appId = params.gameId,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("Epic").e(e, "[Cloud Saves] Sync failed for ${params.appId}")
            return CloudSyncOutcome.ShowDialog(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.sync_error_title),
                    message = context.getString(R.string.main_sync_failed, e.message ?: e.toString()),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
        } finally {
            Timber.tag("Epic").i("[Ownership Tokens] Cleaning up launch tokens for Epic games...")
            EpicService.cleanupLaunchTokens(context)
        }
        return CloudSyncOutcome.Proceed
    }

    override suspend fun upload(
        context: Context,
        appId: String,
        gameId: Int,
        isOffline: Boolean,
        prefixToPath: (String) -> String,
    ) {
        if (isOffline) {
            Timber.tag("Epic").i("[Cloud Saves] Skipping upload for $appId (isOffline=true)")
            return
        }
        Timber.tag("Epic").i("[Cloud Saves] Epic Game detected for $appId — uploading cloud saves after close")
        try {
            app.gamenative.service.epic.EpicCloudSavesManager.syncCloudSaves(
                context = context,
                appId = gameId,
                preferredAction = "upload",
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("Epic").e(e, "[Cloud Saves] Upload failed for $appId")
        }
    }
}
