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

    /**
     * Upload local cloud saves after the game has exited.
     * Only the platform that applies to this app runs; others no-op.
     * [prefixToPath] is used by Steam to resolve cloud save paths; other platforms ignore it.
     */
    suspend fun upload(
        context: Context,
        appId: String,
        gameId: Int,
        isOffline: Boolean,
        prefixToPath: (String) -> String,
    ) {
        // Default: no-op (platforms that support post-exit upload override this).
    }
}
