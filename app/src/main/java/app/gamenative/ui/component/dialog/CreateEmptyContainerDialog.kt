package app.gamenative.ui.component.dialog

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.utils.ContainerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@Composable
fun CreateEmptyContainerDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onContainerCreated: () -> Unit = {}
) {
    if (visible) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var containerName by remember { mutableStateOf("") }
        var selectedFolder by remember { mutableStateOf<File?>(null) }
        var isCreating by remember { mutableStateOf(false) }
        var showFolderBrowser by remember { mutableStateOf(false) }
        var showPermissionDialog by remember { mutableStateOf(false) }
        
        // Launcher for MANAGE_EXTERNAL_STORAGE permission (Android 11+)
        val manageStoragePermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(context, "Full storage access granted!", Toast.LENGTH_SHORT).show()
                    // Now show the folder browser
                    showFolderBrowser = true
                } else {
                    Toast.makeText(context, "Storage permission denied. Cannot browse folders.", Toast.LENGTH_LONG).show()
                }
            }
        }
        
        // Simple folder browser
        SimpleFolderBrowser(
            visible = showFolderBrowser,
            onDismissRequest = { showFolderBrowser = false },
            onFolderSelected = { folder ->
                selectedFolder = folder
                Timber.d("Selected folder: ${folder.absolutePath}")
            }
        )
        
        // Permission explanation dialog
        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text("Storage Access Required") },
                text = {
                    Text(
                        "To browse and import game folders from anywhere on your device, " +
                        "GameNative needs 'All files access' permission.\n\n" +
                        "This allows you to:\n" +
                        "• Access folders on external SD cards\n" +
                        "• Browse internal storage freely\n" +
                        "• Import portable Windows games\n\n" +
                        "Tap 'Grant Access' to open Settings, then enable 'All files access' for GameNative."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPermissionDialog = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                manageStoragePermissionLauncher.launch(intent)
                            }
                        }
                    ) {
                        Text("Grant Access")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionDialog = false }) {
                        Text("Later")
                    }
                }
            )
        }

        AlertDialog(
            onDismissRequest = { if (!isCreating) onDismissRequest() },
            title = { Text("Create Custom Container") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Create a container and optionally import a folder with your game/application files.")
                    
                    OutlinedTextField(
                        value = containerName,
                        onValueChange = { containerName = it },
                        label = { Text("Container Name") },
                        placeholder = { Text("My Custom Container") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isCreating
                    )
                    
                    // Folder selection button
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Import Folder (Optional)",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                        )
                        
                        OutlinedButton(
                            onClick = { 
                                // Check if we have full storage access on Android 11+
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val hasPermission = Environment.isExternalStorageManager()
                                    Timber.d("MANAGE_EXTERNAL_STORAGE permission: $hasPermission")
                                    
                                    if (!hasPermission) {
                                        // Show permission dialog first
                                        showPermissionDialog = true
                                    } else {
                                        // Already have permission, show browser
                                        showFolderBrowser = true
                                    }
                                } else {
                                    // Android 10 and below - just show browser
                                    Timber.d("Android ${Build.VERSION.SDK_INT} - no MANAGE_EXTERNAL_STORAGE needed")
                                    showFolderBrowser = true
                                }
                            },
                            enabled = !isCreating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                selectedFolder?.let { "Selected: ${it.name}" }
                                    ?: "Browse for Folder",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        if (selectedFolder != null) {
                            Text(
                                selectedFolder!!.absolutePath,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        // Show hint if no storage permission on Android 11+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            Text(
                                "💡 Tip: Grant 'All files access' to browse all folders on your device",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = containerName.ifBlank { "Custom Container" }
                        isCreating = true
                        
                        // Create container with optional folder import
                        scope.launch(Dispatchers.IO) {
                            try {
                                val container = ContainerUtils.createEmptyContainer(context, name)
                                
                                // If a folder was selected, import it
                                selectedFolder?.let { folder ->
                                    if (!folder.exists()) {
                                        throw IllegalArgumentException("Folder no longer exists: ${folder.absolutePath}")
                                    }
                                    if (!folder.isDirectory) {
                                        throw IllegalArgumentException("Path is not a directory: ${folder.absolutePath}")
                                    }
                                    
                                    Timber.d("Importing folder: ${folder.absolutePath}")
                                    ContainerUtils.importFolderToContainer(
                                        context,
                                        container.id,
                                        folder
                                    )
                                }
                                
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Container created successfully",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onContainerCreated()
                                    onDismissRequest()
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to create container")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Failed to create container: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    isCreating = false
                                }
                            }
                        }
                    },
                    enabled = !isCreating
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text(if (isCreating) "Creating..." else "Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissRequest,
                    enabled = !isCreating
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
