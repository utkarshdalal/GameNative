package app.gamenative.ui.screen.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.gamenative.PrefManager
import app.gamenative.utils.CustomGameScanner
import com.alorma.compose.settings.ui.SettingsGroup

/**
 * Converts a document tree URI to a file path.
 * Returns null if conversion fails.
 */
fun getPathFromTreeUri(uri: Uri?): String? {
    if (uri == null) return null
    
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            
            // Handle primary storage (internal storage)
            if (docId.startsWith("primary:")) {
                val path = docId.substringAfter(":")
                val externalStorage = Environment.getExternalStorageDirectory()
                return if (path.isEmpty()) {
                    externalStorage.path
                } else {
                    "${externalStorage.path}/$path"
                }
            }
            
            // Handle other storage volumes (e.g., SD cards, USB drives)
            if (docId.contains(":")) {
                val parts = docId.split(":", limit = 2)
                if (parts.size == 2) {
                    val volumeId = parts[0]
                    val path = parts[1]
                    // Common mount points for external storage
                    // Try /storage/volumeId first (most common)
                    val possiblePath = if (path.isEmpty()) {
                        "/storage/$volumeId"
                    } else {
                        "/storage/$volumeId/$path"
                    }
                    // Verify the path exists (basic check)
                    val file = java.io.File(possiblePath)
                    if (file.exists() || file.parentFile?.exists() == true) {
                        return possiblePath
                    }
                    // Fallback: return the constructed path anyway
                    return possiblePath
                }
            }
            
            // If docId doesn't contain ":", it might be a direct path
            if (!docId.contains(":")) {
                return docId
            }
        }
        
        // Fallback: try to extract from URI path
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
            // Last resort: return the path as-is
            path
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun SettingsGroupCustomGames() {
    val context = LocalContext.current
    var paths by remember { mutableStateOf(PrefManager.customGamePaths.toMutableSet()) }
    var pathToDelete by remember { mutableStateOf<String?>(null) }

    // Counts per root
    var counts by remember { mutableStateOf(CustomGameScanner.countGamesByRoot()) }

    // Permission launcher for Android 10 and below
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Refresh counts after permission is granted
        counts = CustomGameScanner.countGamesByRoot()
    }
    
    // Function to request permissions for a path
    fun requestPermissionsForPath(path: String) {
        val isOutsideSandbox = !path.contains("/Android/data/${context.packageName}") && 
                               !path.contains(context.dataDir.path)
        
        if (!isOutsideSandbox) {
            // Path is in app sandbox, no permission needed
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ requires MANAGE_EXTERNAL_STORAGE
            CustomGameScanner.requestManageExternalStoragePermission(context)
        } else {
            // Android 10 and below use standard storage permissions
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            storagePermissionLauncher.launch(permissions)
        }
    }
    
    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val path = getPathFromTreeUri(selectedUri)
            if (path != null) {
                val copy = paths.toMutableSet()
                copy.add(path)
                paths = copy
                PrefManager.customGamePaths = copy
                // Invalidate cache so new path is scanned
                CustomGameScanner.invalidateCache()
                // Counts will refresh via LaunchedEffect(paths)
                
                // Check if we need to request permissions for this path
                if (!CustomGameScanner.hasStoragePermission(context, path)) {
                    requestPermissionsForPath(path)
                }
            } else {
                Toast.makeText(
                    context,
                    "Failed to get path from selected folder",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Save paths to preferences if default path was added
    LaunchedEffect(paths) {
        // Only save if paths have changed from what's in preferences
        val currentPrefs = PrefManager.customGamePaths
        if (paths != currentPrefs) {
            PrefManager.customGamePaths = paths
        }
    }
    
    // Automatically refresh counts when this section is shown and when paths change
    LaunchedEffect(Unit) {
        counts = CustomGameScanner.countGamesByRoot()
    }
    LaunchedEffect(paths) {
        counts = CustomGameScanner.countGamesByRoot()
    }
    
    // Refresh counts when the app resumes (e.g., user returns from settings after granting permission)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                counts = CustomGameScanner.countGamesByRoot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    SettingsGroup(title = { Text(text = "Custom Games") }) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Paths list (includes default path)
            if (paths.isEmpty()) {
                Text(text = "No paths added")
            } else {
                paths.forEach { path ->
                    val count = counts[path] ?: 0
                    val hasPermission = CustomGameScanner.hasStoragePermission(context, path)
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.padding(vertical = 4.dp))
                                Text(
                                    text = when {
                                        count == -1 -> if (hasPermission) {
                                            "⚠ Cannot access (check if path exists)"
                                        } else {
                                            "⚠ Permission denied"
                                        }
                                        count == 0 -> "0 folders found"
                                        else -> "$count folders found"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (count == -1) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (count == -1 && !hasPermission) {
                                    OutlinedButton(
                                        onClick = { requestPermissionsForPath(path) }
                                    ) {
                                        Text("Grant Permission")
                                    }
                                }
                                IconButton(
                                    onClick = { pathToDelete = path }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Confirmation dialog for path deletion
            pathToDelete?.let { path ->
                AlertDialog(
                    onDismissRequest = { pathToDelete = null },
                    title = { Text("Remove Path") },
                    text = {
                        Text("Are you sure you want to remove this path from the list? The path will be removed from scanning, but the content will not be deleted.")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val copy = paths.toMutableSet()
                                copy.remove(path)
                                paths = copy
                                PrefManager.customGamePaths = copy
                                // Invalidate cache so removed path is no longer scanned
                                CustomGameScanner.invalidateCache()
                                // Counts will refresh via LaunchedEffect(paths)
                                
                                Toast.makeText(
                                    context,
                                    "Path removed from list. Content has not been deleted.",
                                    Toast.LENGTH_LONG
                                ).show()
                                
                                pathToDelete = null
                            }
                        ) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pathToDelete = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.padding(vertical = 8.dp))
            
            // Full-width Add Path button
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                onClick = {
                    // Check permissions before opening folder picker
                    // For Android 11+, we'll check after selection since MANAGE_EXTERNAL_STORAGE
                    // might be needed, but the picker itself doesn't require it
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        // For Android 10 and below, check if we have storage permissions
                        val hasReadPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                        
                        if (!hasReadPermission) {
                            // Request permissions first
                            val permissions = arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                            storagePermissionLauncher.launch(permissions)
                            return@Button
                        }
                    }
                    
                    // Open folder picker
                    folderPickerLauncher.launch(null)
                }
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Add path")
            }

            Text(
                text = "Folders in these paths are scanned for .exe files and listed as custom games.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
