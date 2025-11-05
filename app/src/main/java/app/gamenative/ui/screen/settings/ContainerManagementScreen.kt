package app.gamenative.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.CreateEmptyContainerDialog
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerManagementScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var containers by remember { mutableStateOf<List<Container>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedContainer by remember { mutableStateOf<Container?>(null) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var containerToDelete by remember { mutableStateOf<Container?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    
    // Load containers
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val customContainers = ContainerUtils.getCustomContainers(context)
            withContext(Dispatchers.Main) {
                containers = customContainers
                isLoading = false
            }
        }
    }
    
    // Reload containers function
    val reloadContainers: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            val customContainers = ContainerUtils.getCustomContainers(context)
            withContext(Dispatchers.Main) {
                containers = customContainers
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Container Management") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, "Create Container")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                containers.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No custom containers",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Create a container to get started",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Create Container")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(containers) { container ->
                            ContainerCard(
                                container = container,
                                onLaunch = {
                                    PluviaApp.events.emit(AndroidEvent.LaunchContainerToDesktop(container.id))
                                },
                                onEdit = {
                                    selectedContainer = container
                                    showConfigDialog = true
                                },
                                onDuplicate = {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            ContainerUtils.duplicateContainer(context, container)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    "Container duplicated",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                reloadContainers()
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    "Failed to duplicate: ${e.message}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                onDelete = {
                                    containerToDelete = container
                                    showDeleteConfirmation = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Create container dialog
    if (showCreateDialog) {
        CreateEmptyContainerDialog(
            visible = showCreateDialog,
            onDismissRequest = { showCreateDialog = false },
            onContainerCreated = {
                showCreateDialog = false
                reloadContainers()
            }
        )
    }
    
    // Config editor dialog
    if (showConfigDialog && selectedContainer != null) {
        ContainerConfigDialog(
            visible = showConfigDialog,
            title = selectedContainer!!.name,
            default = false,
            initialConfig = app.gamenative.utils.ContainerUtils.containerToData(selectedContainer!!),
            containerId = selectedContainer!!.id,
            onDismissRequest = {
                showConfigDialog = false
                selectedContainer = null
                reloadContainers()
            },
            onSave = { newConfig ->
                // Save the config to the container
                scope.launch(Dispatchers.IO) {
                    try {
                        app.gamenative.utils.ContainerUtils.updateContainerConfig(
                            context,
                            selectedContainer!!.id,
                            newConfig
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Container updated",
                                Toast.LENGTH_SHORT
                            ).show()
                            showConfigDialog = false
                            selectedContainer = null
                            reloadContainers()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Failed to update: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        )
    }
    
    // Delete confirmation dialog
    if (showDeleteConfirmation && containerToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmation = false
                containerToDelete = null
            },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null)
            },
            title = {
                Text("Delete Container?")
            },
            text = {
                Text("Are you sure you want to delete \"${containerToDelete!!.name}\"? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val container = containerToDelete!!
                        scope.launch(Dispatchers.IO) {
                            try {
                                ContainerUtils.deleteContainer(context, container.id)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Container deleted",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showDeleteConfirmation = false
                                    containerToDelete = null
                                    reloadContainers()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Failed to delete: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        containerToDelete = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ContainerCard(
    container: Container,
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = container.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ID: ${container.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Launch button
                OutlinedButton(
                    onClick = onLaunch,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Launch")
                }
                
                // Edit button
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Duplicate button
                OutlinedButton(
                    onClick = onDuplicate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Duplicate")
                }
                
                // Delete button
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}
