package app.gamenative.ui.component.dialog

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import timber.log.Timber
import java.io.File

@Composable
fun SimpleFolderBrowser(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onFolderSelected: (File) -> Unit
) {
    if (visible) {
        var currentPath by remember { mutableStateOf(getInitialPath()) }
        var folders by remember(currentPath) { 
            mutableStateOf(getFoldersInPath(currentPath))
        }
        var isLoading by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { 
                Column {
                    Text("Select Folder")
                    Text(
                        currentPath.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Parent directory option
                            if (currentPath.parent != null) {
                                item {
                                    FolderItem(
                                        name = ".. (Parent folder)",
                                        icon = Icons.Default.ArrowUpward,
                                        isSpecial = true,
                                        onClick = {
                                            currentPath.parentFile?.let { parent ->
                                                currentPath = parent
                                                folders = getFoldersInPath(currentPath)
                                            }
                                        }
                                    )
                                }
                            }
                            
                            // Quick access storage locations (if at root)
                            if (currentPath.absolutePath == "/storage") {
                                item {
                                    StorageLocationItem(
                                        name = "Internal Storage",
                                        path = Environment.getExternalStorageDirectory().absolutePath,
                                        icon = Icons.Default.PhoneAndroid,
                                        onClick = {
                                            currentPath = Environment.getExternalStorageDirectory()
                                            folders = getFoldersInPath(currentPath)
                                        }
                                    )
                                }
                                
                                // SD Card detection
                                getExternalStoragePaths().forEach { sdPath ->
                                    item {
                                        StorageLocationItem(
                                            name = "SD Card",
                                            path = sdPath.absolutePath,
                                            icon = Icons.Default.SdCard,
                                            onClick = {
                                                currentPath = sdPath
                                                folders = getFoldersInPath(currentPath)
                                            }
                                        )
                                    }
                                }
                                
                                item { 
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) 
                                }
                            }
                            
                            // Quick shortcuts from internal storage root
                            if (currentPath == Environment.getExternalStorageDirectory()) {
                                val commonFolders = listOf(
                                    "Download" to Icons.Default.Download,
                                    "Documents" to Icons.Default.Description,
                                    "Games" to Icons.Default.SportsEsports
                                )
                                
                                commonFolders.forEach { (folderName, icon) ->
                                    val folder = File(currentPath, folderName)
                                    if (folder.exists() && folder.isDirectory && folder.canRead()) {
                                        item {
                                            FolderItem(
                                                name = folderName,
                                                icon = icon,
                                                isSpecial = true,
                                                onClick = {
                                                    currentPath = folder
                                                    folders = getFoldersInPath(currentPath)
                                                }
                                            )
                                        }
                                    }
                                }
                                
                                if (commonFolders.any { File(currentPath, it.first).exists() }) {
                                    item { 
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) 
                                    }
                                }
                            }
                            
                            // Regular folders in current directory
                            items(folders) { folder ->
                                FolderItem(
                                    name = folder.name,
                                    icon = Icons.Default.Folder,
                                    onClick = {
                                        isLoading = true
                                        currentPath = folder
                                        folders = getFoldersInPath(currentPath)
                                        isLoading = false
                                    }
                                )
                            }
                            
                            // Empty state
                            if (folders.isEmpty() && currentPath.parent != null) {
                                item {
                                    Text(
                                        "No accessible folders",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onFolderSelected(currentPath)
                        onDismissRequest()
                    }
                ) {
                    Text("Select This Folder")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FolderItem(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Folder,
    isSpecial: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = if (isSpecial) 2.dp else 1.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSpecial) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSpecial) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun StorageLocationItem(
    name: String,
    path: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Column {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

private fun getInitialPath(): File {
    // Start at storage root to show all storage options
    return File("/storage")
}

private fun getFoldersInPath(path: File): List<File> {
    return try {
        path.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.filter { 
                try {
                    it.canRead()
                } catch (e: SecurityException) {
                    false
                }
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    } catch (e: Exception) {
        Timber.e(e, "Error reading directory: ${path.absolutePath}")
        emptyList()
    }
}

private fun getExternalStoragePaths(): List<File> {
    val externalPaths = mutableListOf<File>()
    
    try {
        // Check common SD card mount points
        File("/storage").listFiles()?.forEach { storageDir ->
            // Skip emulated (internal) storage
            if (!storageDir.name.contains("emulated") && 
                !storageDir.name.equals("self", ignoreCase = true) &&
                storageDir.isDirectory && 
                storageDir.canRead()) {
                externalPaths.add(storageDir)
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "Error detecting external storage")
    }
    
    return externalPaths
}
