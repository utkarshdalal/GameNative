package app.gamenative.ui.components

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.gamenative.R
import app.gamenative.utils.CustomGameScanner

/**
 * Resolves the filesystem root of a storage volume identified by the volume id
 * from a document tree URI (e.g. "6A1F-93F0").
 *
 * SD cards typically live at /storage/<uuid>, but USB OTG drives on some devices
 * (e.g. Samsung) are only mounted at the path reported by
 * [android.os.storage.StorageVolume.getDirectory] (such as /mnt/media_rw/<uuid>),
 * with no /storage view at all.
 *
 * @param context Used to query [android.os.storage.StorageManager] for mounted volumes.
 * @param volumeId The volume id portion of a tree document id (the part before ":").
 * @return The volume's mount point. Prefers /storage/<volumeId> when it exists,
 *         otherwise the directory reported by StorageManager, falling back to
 *         /storage/<volumeId> when the volume cannot be resolved.
 */
private fun resolveVolumeRoot(context: Context, volumeId: String): String {
    val defaultRoot = "/storage/$volumeId"
    if (java.io.File(defaultRoot).exists()) {
        return defaultRoot
    }
    val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
    val volume = sm?.storageVolumes?.firstOrNull {
        it.uuid?.equals(volumeId, ignoreCase = true) == true
    }
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        volume?.directory?.absolutePath
    } else {
        null
    }
    return resolved ?: defaultRoot
}

/**
 * Converts a document tree URI (from e.g. [ActivityResultContracts.OpenDocumentTree])
 * to a raw filesystem path.
 *
 * Handles the primary volume ("primary:" document ids) as well as secondary volumes
 * such as SD cards and USB OTG drives, whose mount point is resolved via
 * [resolveVolumeRoot].
 *
 * @param context Used to resolve secondary volume mount points.
 * @param uri The tree URI returned by the document picker, or null.
 * @return The resolved filesystem path, or null if [uri] is null or conversion fails.
 */
fun getPathFromTreeUri(context: Context, uri: Uri?): String? {
    if (uri == null) return null

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val docId = DocumentsContract.getTreeDocumentId(uri)

            if (docId.startsWith("primary:")) {
                val path = docId.substringAfter(":")
                val externalStorage = Environment.getExternalStorageDirectory()
                return if (path.isEmpty()) {
                    externalStorage.path
                } else {
                    "${externalStorage.path}/$path"
                }
            }

            if (docId.contains(":")) {
                val parts = docId.split(":", limit = 2)
                if (parts.size == 2) {
                    val volumeId = parts[0]
                    val path = parts[1]
                    val volumeRoot = resolveVolumeRoot(context, volumeId)
                    return if (path.isEmpty()) {
                        volumeRoot
                    } else {
                        "$volumeRoot/$path"
                    }
                }
            }

            if (!docId.contains(":")) {
                return docId
            }
        }

        uri.path?.let { path ->
            if (path.startsWith("/tree/")) {
                val docId = path.substringAfter("/tree/")
                if (docId.startsWith("primary:")) {
                    val filePath = docId.substringAfter(":")
                    val externalStorage = Environment.getExternalStorageDirectory()
                    return if (filePath.isEmpty()) {
                        externalStorage.path
                    } else {
                        "${externalStorage.path}/$filePath"
                    }
                }
            }
            path
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Ensures we have the correct permissions for the provided path.
 */
fun requestPermissionsForPath(
    context: Context,
    path: String,
    storagePermissionLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>?,
) {
    val isOutsideSandbox = !path.contains("/Android/data/${context.packageName}") &&
        !path.contains(context.dataDir.path)

    if (!isOutsideSandbox) {
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        CustomGameScanner.requestManageExternalStoragePermission(context)
    } else {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        storagePermissionLauncher?.launch(permissions)
    }
}

data class CustomGameFolderPicker(
    val launchPicker: () -> Unit,
)

/**
 * Helper for remembering a folder picker launcher that returns a resolved file path.
 */
@Composable
fun rememberCustomGameFolderPicker(
    onPathSelected: (String) -> Unit,
    onFailure: (String) -> Unit = {},
    onCancel: () -> Unit = {},
): CustomGameFolderPicker {
    val context = LocalContext.current
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) {
            onCancel()
            return@rememberLauncherForActivityResult
        }

        val path = getPathFromTreeUri(context, uri)
        if (path != null) {
            onPathSelected(path)
        } else {
            onFailure(context.getString(R.string.custom_game_folder_picker_error))
        }
    }

    return remember {
        CustomGameFolderPicker(
            launchPicker = { pickerLauncher.launch(null) },
        )
    }
}

