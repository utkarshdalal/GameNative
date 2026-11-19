package app.gamenative.ui.components

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.BuildConfig
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
 * Handles the primary volume ("primary:" document ids), secondary volumes
 * such as SD cards and USB OTG drives, whose mount point is resolved via
 * [resolveVolumeRoot], and the Downloads entry of the system picker.
 *
 * @param context Used to resolve secondary volume mount points.
 * @param uri The tree URI returned by the document picker, or null.
 * @return The resolved filesystem path, or null if [uri] is null or conversion fails.
 */
fun getPathFromTreeUri(context: Context, uri: Uri?): String? {
    if (uri == null) return null

    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> externalStoragePath(context, docId)
            DOWNLOADS_AUTHORITY -> downloadsPath(context, docId)
            // other providers have their own ids. only an id that is already a path can be used.
            else -> docId.takeIf { it.startsWith("/") }
        }
    } catch (e: Exception) {
        null
    }
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

private fun externalStoragePath(context: Context, docId: String): String? {
    if (!docId.contains(":")) return docId.takeIf { it.startsWith("/") }
    val volumeId = docId.substringBefore(":")
    val path = docId.substringAfter(":")
    val root = if (volumeId == "primary") {
        Environment.getExternalStorageDirectory().path
    } else {
        resolveVolumeRoot(context, volumeId)
    }
    return if (path.isEmpty()) root else "$root/$path"
}

/**
 * The Downloads entry of the system folder picker. Its ids are not storage paths:
 * "downloads" is the Download folder, "raw:<path>" carries a path, and
 * "msd:<id>" is a MediaStore row id of a folder inside Download.
 */
private fun downloadsPath(context: Context, docId: String): String? = when {
    docId == "downloads" ->
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
    docId.startsWith("raw:") -> docId.substringAfter(":").takeIf { it.startsWith("/") }
    docId.startsWith("msd:") -> docId.substringAfter(":").toLongOrNull()?.let { mediaStorePath(context, it) }
    else -> null
}

private fun needsAllFilesAccess(uri: Uri): Boolean =
    !BuildConfig.MODERN_ANDROID &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        uri.authority == DOWNLOADS_AUTHORITY &&
        DocumentsContract.getTreeDocumentId(uri).startsWith("msd:") &&
        !Environment.isExternalStorageManager()

private fun mediaStorePath(context: Context, id: Long): String? {
    val row = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
    return context.contentResolver.query(row, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.startsWith("/") } else null
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
    val currentOnPathSelected by rememberUpdatedState(onPathSelected)
    val currentOnFailure by rememberUpdatedState(onFailure)
    var pendingUri by rememberSaveable { mutableStateOf<Uri?>(null) }
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
        } else if (needsAllFilesAccess(uri) && CustomGameScanner.requestManageExternalStoragePermission(context)) {
            pendingUri = uri
        } else {
            onFailure(context.getString(R.string.custom_game_folder_picker_error))
        }
    }

    // a Downloads folder is only readable from MediaStore with all files access: finish when the user comes back with it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val uri = pendingUri
            if (event == Lifecycle.Event.ON_RESUME && uri != null && !needsAllFilesAccess(uri)) {
                pendingUri = null
                val path = getPathFromTreeUri(context, uri)
                if (path != null) {
                    currentOnPathSelected(path)
                } else {
                    currentOnFailure(context.getString(R.string.custom_game_folder_picker_error))
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember {
        CustomGameFolderPicker(
            launchPicker = {
                pendingUri = null
                pickerLauncher.launch(null)
            },
        )
    }
}

