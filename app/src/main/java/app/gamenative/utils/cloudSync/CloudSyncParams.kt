package app.gamenative.utils.cloudSync

import app.gamenative.enums.SaveLocation
import kotlinx.coroutines.CoroutineScope

/**
 * Parameters for cloud sync (Steam needs most of these).
 * [syncCloudSaves] resolves all sync outcomes and returns Proceed / ShowDialog / Retry.
 */
data class CloudSyncParams(
    val appId: String,
    val gameId: Int,
    val ignorePendingOperations: Boolean = false,
    val preferredSave: SaveLocation = SaveLocation.None,
    val useTemporaryOverride: Boolean = false,
    val retryCount: Int = 0,
    val isOffline: Boolean = false,
    val scope: CoroutineScope,
)
