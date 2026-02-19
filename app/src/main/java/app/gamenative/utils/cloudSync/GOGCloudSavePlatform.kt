package app.gamenative.utils.cloudSync

import android.content.Context
import app.gamenative.R
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.enums.DialogType
import app.gamenative.utils.ContainerUtils
import app.gamenative.data.GameSource
import app.gamenative.utils.CloudSaveCallbacks
import com.winlator.container.Container
import timber.log.Timber
import kotlinx.coroutines.CancellationException

/** GOG cloud save sync before launch. */
internal object GOGCloudSavePlatform : CloudSavePlatform {
    override fun appliesTo(container: Container) =
        ContainerUtils.extractGameSourceFromContainerId(container.id) == GameSource.GOG

    override fun getLoadingMessage(context: Context, container: Container) =
        context.getString(R.string.main_syncing_cloud_saves)

    override suspend fun sync(
        context: Context,
        container: Container,
        params: CloudSyncParams,
        callbacks: CloudSaveCallbacks,
    ): CloudSyncOutcome {
        Timber.tag("GOG").i("[Cloud Saves] GOG Game detected for ${params.appId} — syncing cloud saves before launch")
        return try {
            app.gamenative.service.gog.GOGService.syncCloudSaves(
                context = context,
                appId = params.appId,
            )
            CloudSyncOutcome.Proceed
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("GOG").e(e, "[Cloud Saves] Sync failed for ${params.appId}")
            CloudSyncOutcome.ShowDialog(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.sync_error_title),
                    message = context.getString(R.string.main_sync_failed, e.message ?: e.toString()),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
        }
    }

    override suspend fun upload(
        context: Context,
        appId: String,
        gameId: Int,
        isOffline: Boolean,
        prefixToPath: (String) -> String,
    ) {
        Timber.tag("GOG").i("[Cloud Saves] GOG Game detected for $appId — uploading cloud saves after close")
        try {
            app.gamenative.service.gog.GOGService.syncCloudSaves(
                context = context,
                appId = appId,
                preferredAction = "upload",
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("GOG").e(e, "[Cloud Saves] Upload failed for $appId")
        }
    }
}
