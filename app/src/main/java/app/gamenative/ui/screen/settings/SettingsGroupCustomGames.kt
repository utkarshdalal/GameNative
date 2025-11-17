package app.gamenative.ui.screen.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@Composable
fun SettingsGroupCustomGames() {
    val context = LocalContext.current
    var paths by remember { mutableStateOf(PrefManager.customGamePaths.toMutableSet()) }
    var newPath by remember { mutableStateOf("") }

    val defaultPath = CustomGameScanner.defaultRootPath

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

    val lifecycleOwner = LocalLifecycleOwner.current
    
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
            Text(text = "Default root (always scanned):")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = defaultPath)
                    Text(text = "${counts[defaultPath] ?: 0} folders found")
                }
            }

            // Existing extra paths list
            if (paths.isEmpty()) {
                Text(text = "No additional paths added")
            } else {
                paths.forEach { path ->
                    val count = counts[path] ?: 0
                    val hasPermission = CustomGameScanner.hasStoragePermission(context, path)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = path)
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
                                color = if (count == -1) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.error
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (count == -1 && !hasPermission) {
                                OutlinedButton(
                                    onClick = { requestPermissionsForPath(path) }
                                ) {
                                    Text("Grant Permission")
                                }
                            }
                            IconButton(onClick = {
                                val copy = paths.toMutableSet()
                                copy.remove(path)
                                paths = copy
                                PrefManager.customGamePaths = copy
                                // Invalidate cache so removed path is no longer scanned
                                CustomGameScanner.invalidateCache()
                                // Counts will refresh via LaunchedEffect(paths)
                            }) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("Add path") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.heightIn(min = 56.dp),
                    onClick = {
                        val path = newPath.trim()
                        if (path.isNotEmpty()) {
                            val copy = paths.toMutableSet()
                            copy.add(path)
                            paths = copy
                            PrefManager.customGamePaths = copy
                            // Invalidate cache so new path is scanned
                            CustomGameScanner.invalidateCache()
                            newPath = ""
                            // Counts will refresh via LaunchedEffect(paths)
                            
                            // Check if we need to request permissions for this path
                            if (!CustomGameScanner.hasStoragePermission(context, path)) {
                                requestPermissionsForPath(path)
                            }
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Text(text = "Add")
                }
            }

            Text(text = "Folders in these paths are scanned for .exe files and listed as custom games.", modifier = Modifier.padding(top = 8.dp))
        }
    }
}
