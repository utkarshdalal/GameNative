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
 * Converts a document tree URI to a file path.
 * Returns null if conversion fails.
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
                    // Resolve the real mount point for this volume. SD cards typically live at
                    // /storage/<uuid>, but USB OTG drives on some devices (e.g. Samsung) are only
                    // mounted at the path reported by StorageVolume.getDirectory()
                    // (e.g. /mnt/media_rw/<uuid>), with no /storage view at all.
                    val defaultRoot = "/storage/$volumeId"
                    val volumeRoot = if (java.io.File(defaultRoot).exists()) {
                        defaultRoot
                    } else {
                        val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
                        val volume = sm?.storageVolumes?.firstOrNull {
                            it.uuid?.equals(volumeId, ignoreCase = true) == true
                        }
                        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            volume?.directory?.absolutePath
                        } else {
                            null
                        }
                        resolved ?: defaultRoot
                    }
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

