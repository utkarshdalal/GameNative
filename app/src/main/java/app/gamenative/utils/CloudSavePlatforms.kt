package app.gamenative.utils

import android.content.Context
import app.gamenative.utils.cloudSync.CloudSavePlatform
import app.gamenative.utils.cloudSync.CloudSyncOutcome
import app.gamenative.utils.cloudSync.CloudSyncParams
import app.gamenative.utils.cloudSync.EpicCloudSavePlatform
import app.gamenative.utils.cloudSync.GOGCloudSavePlatform
import app.gamenative.utils.cloudSync.SteamCloudSavePlatform
import com.winlator.container.Container

private val ALL_CLOUD_SAVE_PLATFORMS: List<CloudSavePlatform> = listOf(
    GOGCloudSavePlatform,
    EpicCloudSavePlatform,
    SteamCloudSavePlatform,
)

/**
 * Returns the cloud save platform that applies to this container (at most one).
 */
fun getCloudSyncPlatforms(container: Container): List<CloudSavePlatform> =
    ALL_CLOUD_SAVE_PLATFORMS.filter { it.appliesTo(container) }

/** Callbacks for progress during cloud save. */
data class CloudSaveCallbacks(
    val setLoadingMessage: (String) -> Unit,
    val setLoadingProgress: (Float) -> Unit,
)

/**
 * Sync (download/merge) cloud saves before launch.
 * Returns [CloudSyncOutcome] (Proceed, ShowDialog, or Retry); caller applies the outcome.
 */
suspend fun syncCloudSaves(
    context: Context,
    container: Container,
    params: CloudSyncParams,
    setLoadingMessage: (String) -> Unit,
    setLoadingProgress: (Float) -> Unit,
): CloudSyncOutcome {
    val callbacks = CloudSaveCallbacks(setLoadingMessage, setLoadingProgress)
    val platforms = getCloudSyncPlatforms(container)
    return if (platforms.isEmpty()) {
        CloudSyncOutcome.Proceed
    } else {
        val platform = platforms.single()
        setLoadingMessage(platform.getLoadingMessage(context, container))
        platform.sync(context, container, params, callbacks)
    }
}
