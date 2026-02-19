package app.gamenative.utils.cloudSync

import android.content.Context
import app.gamenative.R
import app.gamenative.utils.ContainerUtils
import app.gamenative.data.GameSource
import app.gamenative.utils.CloudSaveCallbacks
import com.winlator.container.Container
import timber.log.Timber

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
        app.gamenative.service.gog.GOGService.syncCloudSaves(
            context = context,
            appId = params.appId,
        )
        return CloudSyncOutcome.Proceed
    }
}
