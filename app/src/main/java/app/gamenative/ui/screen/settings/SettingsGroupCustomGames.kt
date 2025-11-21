package app.gamenative.ui.screen.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.components.requestPermissionsForPath
import app.gamenative.ui.components.rememberCustomGameFolderPicker
import app.gamenative.utils.CustomGameScanner
import com.alorma.compose.settings.ui.SettingsGroup

@Composable
fun SettingsGroupCustomGames() {
    val context = LocalContext.current
    var paths by remember { mutableStateOf(PrefManager.customGamePaths.toMutableSet()) }
    var pathToDelete by remember { mutableStateOf<String?>(null) }
    var manualFolders by remember { mutableStateOf(PrefManager.customGameManualFolders.toMutableSet()) }
    var manualFolderToDelete by remember { mutableStateOf<String?>(null) }

    // Counts per root
    var counts by remember { mutableStateOf(CustomGameScanner.countGamesByRoot()) }

    // Permission launcher for Android 10 and below
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Refresh counts after permission is granted
        counts = CustomGameScanner.countGamesByRoot()
    }

    val folderPicker = rememberCustomGameFolderPicker(
        onPathSelected = { path ->
            val copy = paths.toMutableSet()
            copy.add(path)
            paths = copy
            PrefManager.customGamePaths = copy
            CustomGameScanner.invalidateCache()

            // When a folder is selected via OpenDocumentTree, the user has already granted
            // URI permissions for that specific folder. We should verify we can access it
            // rather than checking for broad storage permissions.
            val folder = java.io.File(path)
            val canAccess = try {
                folder.exists() && (folder.isDirectory && folder.canRead())
            } catch (e: Exception) {
                false
            }
            
            // Only request permissions if we can't access the folder AND it's outside the sandbox
            // (folders selected via OpenDocumentTree should already be accessible)
            if (!canAccess && !CustomGameScanner.hasStoragePermission(context, path)) {
                requestPermissionsForPath(context, path, storagePermissionLauncher)
            }
        },
        onFailure = { message ->
            Toast.makeText(
                context,
                message,
                Toast.LENGTH_SHORT,
            ).show()
        },
    )

    val lifecycleOwner = LocalLifecycleOwner.current

    // Save paths to preferences if default path was added
    LaunchedEffect(paths) {
        // Only save if paths have changed from what's in preferences
        val currentPrefs = PrefManager.customGamePaths
        if (paths != currentPrefs) {
            PrefManager.customGamePaths = paths
        }
    }

    LaunchedEffect(manualFolders) {
        val currentPrefs = PrefManager.customGameManualFolders
        if (manualFolders != currentPrefs) {
            PrefManager.customGameManualFolders = manualFolders
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

    SettingsGroup(title = { Text(text = stringResource(id = R.string.custom_games_title)) }) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Paths list (includes default path)
            if (paths.isEmpty()) {
                Text(text = stringResource(id = R.string.custom_games_no_paths))
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
                                            stringResource(R.string.custom_games_cannot_access)
                                        } else {
                                            stringResource(R.string.custom_games_permission_denied)
                                        }
                                        count == 0 -> stringResource(R.string.custom_games_zero_folders)
                                        else -> stringResource(R.string.custom_games_folders_found, count)
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
                                        onClick = { requestPermissionsForPath(context, path, storagePermissionLauncher) }
                                    ) {
                                        Text(text = stringResource(id = R.string.custom_games_grant_permission))
                                    }
                                }
                                IconButton(
                                    onClick = { pathToDelete = path }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(id = R.string.custom_games_remove_path_content_desc),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.padding(vertical = 8.dp))

            if (manualFolders.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.custom_games_no_manual_entries),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                manualFolders.forEach { folder ->
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
                                    text = folder,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.padding(vertical = 4.dp))
                                Text(
                                    text = stringResource(id = R.string.custom_games_manual_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { manualFolderToDelete = folder }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(id = R.string.custom_games_remove_manual_content_desc),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Confirmation dialog for path deletion
            pathToDelete?.let { path ->
                AlertDialog(
                    onDismissRequest = { pathToDelete = null },
                    title = { Text(text = stringResource(id = R.string.custom_games_remove_path_title)) },
                    text = {
                        Text(text = stringResource(id = R.string.custom_games_remove_path_message))
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
                                    context.getString(R.string.custom_games_path_removed_toast),
                                    Toast.LENGTH_LONG
                                ).show()

                                pathToDelete = null
                            }
                        ) {
                            Text(
                                text = stringResource(id = R.string.remove),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pathToDelete = null }) {
                            Text(text = stringResource(id = R.string.cancel))
                        }
                    }
                )
            }

            manualFolderToDelete?.let { path ->
                AlertDialog(
                    onDismissRequest = { manualFolderToDelete = null },
                    title = { Text(text = stringResource(id = R.string.custom_games_remove_manual_title)) },
                    text = {
                        Text(text = stringResource(id = R.string.custom_games_remove_manual_message))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val copy = manualFolders.toMutableSet()
                                copy.remove(path)
                                manualFolders = copy
                                PrefManager.customGameManualFolders = copy
                                CustomGameScanner.invalidateCache()

                                Toast.makeText(
                                    context,
                                    context.getString(R.string.custom_games_manual_removed_toast),
                                    Toast.LENGTH_LONG
                                ).show()

                                manualFolderToDelete = null
                            }
                        ) {
                            Text(
                                text = stringResource(id = R.string.remove),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { manualFolderToDelete = null }) {
                            Text(text = stringResource(id = R.string.cancel))
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
                    folderPicker.launchPicker()
                }
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Add path")
            }

            Text(
                text = stringResource(R.string.custom_games_scan_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
