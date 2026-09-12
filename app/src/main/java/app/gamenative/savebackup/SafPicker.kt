package app.gamenative.savebackup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Compose-friendly SAF document-tree picker — the concrete realization of the design's abstract
 * `pickTree(): Uri?` (Requirement 10.1).
 *
 * `pickTree()` cannot be a plain synchronous function because `OpenDocumentTree`'s result is
 * delivered asynchronously to an `ActivityResultCallback`. Following the same
 * `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())` pattern that
 * `rememberCustomGameFolderPicker` uses, this helper launches the system tree picker and calls back
 * with the selected tree `Uri?`. A `null` callback value means the user cancelled.
 *
 * Unlike `rememberCustomGameFolderPicker`, this helper does **not** convert the tree URI to a
 * filesystem path. The selected URI is handed straight to [SafLocationManager.tryPersist] (to
 * request a durable grant) and to `DocumentFile`/`ContentResolver` stream I/O — never a converted
 * path (Requirements 7.2, 10.1).
 */
class SafPicker(
    /** Launches the system document-tree picker. The result arrives via the `onResult` callback. */
    val launchPicker: () -> Unit,
)

/**
 * Remember a [SafPicker] that launches `OpenDocumentTree` and delivers the selected tree `Uri?`.
 *
 * @param onResult invoked with the selected tree URI, or `null` when the user cancelled the picker.
 */
@Composable
fun rememberSafPicker(
    onResult: (Uri?) -> Unit,
): SafPicker {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> onResult(uri) }

    return remember {
        SafPicker(launchPicker = { launcher.launch(null) })
    }
}
