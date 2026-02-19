package app.gamenative.utils.cloudSync

import android.content.Context
import app.gamenative.R
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.ContainerUtils
import app.gamenative.data.GameSource
import app.gamenative.utils.CloudSaveCallbacks
import com.winlator.container.Container
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
        Timber.tag("Epic").i("[Cloud Saves] Epic Game detected for ${params.appId} — syncing cloud saves before launch")
        app.gamenative.service.epic.EpicCloudSavesManager.syncCloudSaves(
            context = context,
            appId = params.gameId,
        )
        Timber.tag("Epic").i("[Ownership Tokens] Cleaning up launch tokens for Epic games...")
        EpicService.cleanupLaunchTokens(context)
        return CloudSyncOutcome.Proceed
    }
}
