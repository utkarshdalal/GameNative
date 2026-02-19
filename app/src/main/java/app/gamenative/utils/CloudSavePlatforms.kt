package app.gamenative.utils

import android.content.Context
import app.gamenative.utils.cloudSync.CloudSavePlatform
import app.gamenative.utils.cloudSync.CloudSyncOutcome
import app.gamenative.utils.cloudSync.CloudSyncParams
import app.gamenative.utils.cloudSync.EpicCloudSavePlatform
import app.gamenative.utils.cloudSync.GOGCloudSavePlatform
import app.gamenative.utils.cloudSync.SteamCloudSavePlatform
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import timber.log.Timber

private val ALL_CLOUD_SAVE_PLATFORMS: List<CloudSavePlatform> = listOf(
    GOGCloudSavePlatform,
    EpicCloudSavePlatform,
    SteamCloudSavePlatform,
)

/**
 * Returns the cloud save platforms that apply to this container (e.g. GOG, Epic, Steam).
 */
fun getCloudSyncPlatforms(container: Container): List<CloudSavePlatform> =
    ALL_CLOUD_SAVE_PLATFORMS.filter { it.appliesTo(container) }

/** Callbacks for progress during cloud save. */
data class CloudSaveCallbacks(
    val setLoadingMessage: (String) -> Unit,
    val setLoadingProgress: (Float) -> Unit,
)

/**
 * Sync (download/merge) cloud saves before launch for all matching platforms.
 * Returns [CloudSyncOutcome] (Proceed, ShowDialog, or Retry); caller applies the outcome.
 * Runs each platform in order; returns the first non-Proceed outcome if any, otherwise Proceed.
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
    for (platform in platforms) {
        setLoadingMessage(platform.getLoadingMessage(context, container))
        when (val outcome = platform.sync(context, container, params, callbacks)) {
            CloudSyncOutcome.Proceed -> { /* continue to next platform */ }
            is CloudSyncOutcome.ShowDialog, is CloudSyncOutcome.Retry -> return outcome
        }
    }
    return CloudSyncOutcome.Proceed
}

/**
 * Upload local cloud saves after the game has exited for all matching platforms.
 * No-op if none apply.
 */
suspend fun uploadCloudSaves(
    context: Context,
    appId: String,
    gameId: Int,
    isOffline: Boolean,
    prefixToPath: (String) -> String,
) {
    val container = try {
        ContainerUtils.getContainer(context, appId)
    } catch (e: Exception) {
        Timber.tag("CloudSavePlatforms").e(e, "uploadCloudSaves: container not found for appId=$appId")
        return
    }
    val platforms = getCloudSyncPlatforms(container)
    for (platform in platforms) {
        platform.upload(context, appId, gameId, isOffline, prefixToPath)
    }
}
