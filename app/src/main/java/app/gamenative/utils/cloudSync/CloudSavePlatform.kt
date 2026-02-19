package app.gamenative.utils.cloudSync

import android.content.Context
import app.gamenative.utils.CloudSaveCallbacks
import com.winlator.container.Container

/**
 * A cloud-sync step that may run before launch (GOG sync, Epic sync, Steam sync).
 * At most one applies per container; runs after [ensureLaunchDependencies] and container activation.
 */
interface CloudSavePlatform {
    /** Whether this sync applies to the given container. */
    fun appliesTo(container: Container): Boolean

    /** Message shown while this sync is running. */
    fun getLoadingMessage(context: Context, container: Container): String

    /** Sync (download/merge) before launch; returns [CloudSyncOutcome] (Proceed, ShowDialog, or Retry). */
    suspend fun sync(
        context: Context,
        container: Container,
        params: CloudSyncParams,
        callbacks: CloudSaveCallbacks,
    ): CloudSyncOutcome
}
