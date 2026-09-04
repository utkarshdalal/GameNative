package app.gamenative.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import app.gamenative.utils.ScreenshotItem
import app.gamenative.utils.ScreenshotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the per-game screenshot list off the configured storage root. Bump [refreshKey] to reload
 * after a capture or delete. Shared by the gallery, the pause-menu tab, and the detail-page preview.
 */
@Composable
fun rememberScreenshots(appId: String, refreshKey: Int = 0): State<List<ScreenshotItem>> {
    val context = LocalContext.current
    return produceState(initialValue = emptyList(), appId, refreshKey) {
        // Disk I/O (directory listing + stat per file); keep it off the main thread. A failed
        // listing (I/O or SecurityException on a user-picked folder) must not crash the UI.
        value = withContext(Dispatchers.IO) {
            runCatching { ScreenshotManager.list(context, appId) }
                .onFailure { Log.w("rememberScreenshots", "Failed to list screenshots for $appId", it) }
                .getOrDefault(emptyList())
        }
    }
}
