package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SnippetFolder
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.gamenative.ui.component.NoExtractOutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.StorageUtils
import app.gamenative.workshop.WorkshopItem
import app.gamenative.workshop.WorkshopManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopManagerDialog(
    visible: Boolean,
    currentEnabledIds: Set<Long>,
    workshopModPath: String,
    gameRootDir: File?,
    winePrefix: String,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    onSave: (Set<Long>) -> Unit,
    onModPathChanged: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val displayInfo = onGetDisplayInfo(context)
    val gameId = displayInfo.gameId

    val workshopItems = remember { mutableStateListOf<WorkshopItem>() }
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }
    var isLoading by remember { mutableStateOf(true) }
    var fetchFailed by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showFolderPicker by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        scrollState.animateScrollTo(0)
        isLoading = true
        fetchFailed = false
        workshopItems.clear()
        selectedIds.clear()
        searchQuery = ""

        val steamClient = SteamService.instance?.steamClient
        val steamId = SteamService.userSteamId
        if (steamClient != null && steamId != null) {
            val result = withContext(Dispatchers.IO) {
                WorkshopManager.getSubscribedItems(gameId, steamClient, steamId)
            }
            if (result.succeeded) {
                workshopItems.addAll(result.items.sortedBy { it.title.lowercase() })
                // Pre-check items that are currently enabled
                result.items.forEach { item ->
                    selectedIds[item.publishedFileId] =
                        currentEnabledIds.contains(item.publishedFileId)
                }
            } else {
                fetchFailed = true
            }
        } else {
            fetchFailed = true
        }
        isLoading = false
    }

    val allSelected by remember {
        derivedStateOf {
            workshopItems.isNotEmpty() && workshopItems.all { selectedIds[it.publishedFileId] == true }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.Start,
            ) {
                // Hero Section with Game Image Background
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                ) {
                    if (displayInfo.heroImageUrl != null) {
                        CoilImage(
                            modifier = Modifier.fillMaxSize(),
                            imageModel = { displayInfo.heroImageUrl },
                            imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                            loading = { LoadingScreen() },
                            failure = {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.primary
                                ) { }
                            },
                            previewPlaceholder = painterResource(R.drawable.testhero),
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary
                        ) { }
                    }

                    // Gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.8f)
                                    )
                                )
                            )
                    )

                    // Back button
                    Box(
                        modifier = Modifier
                            .padding(20.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        BackButton(onClick = onDismissRequest)
                    }

                    // Game title and subtitle
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp)
                    ) {
                        Text(
                            text = displayInfo.name,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.5f),
                                    offset = Offset(0f, 2f),
                                    blurRadius = 10f
                                )
                            ),
                            color = Color.White
                        )
                        Text(
                            text = "Workshop Mods",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }

                // Search bar
                if (!isLoading && !fetchFailed && workshopItems.isNotEmpty()) {
                    NoExtractOutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search mods...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                    )
                }

                // Content
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = "Loading subscribed mods...",
                                        modifier = Modifier.padding(top = 16.dp),
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        fetchFailed -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Failed to fetch workshop subscriptions. Check your connection and try again.",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        workshopItems.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No subscribed workshop mods found for this game.",
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }

                        else -> {
                            val filteredItems = if (searchQuery.isBlank()) {
                                workshopItems.toList()
                            } else {
                                workshopItems.filter {
                                    it.title.contains(searchQuery, ignoreCase = true)
                                }
                            }

                            // Mod Folder + Select All row
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = { showFolderPicker = true },
                                    ) {
                                        Icon(
                                            imageVector = if (workshopModPath.isNotEmpty()) Icons.Default.FolderOpen else Icons.Default.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Override Mod Folder",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val newState = !allSelected
                                            workshopItems.forEach { item ->
                                                selectedIds[item.publishedFileId] = newState
                                            }
                                        }
                                ) {
                                    Text(
                                        text = if (allSelected) "Deselect all" else "Select all"
                                    )
                                }
                                }

                                // Mod folder path subtitle
                                val modFolderDisplay = if (workshopModPath.isNotEmpty()) {
                                    // Convert Linux path to Windows-style for readability
                                    val driveCMarker = "/drive_c/"
                                    val driveCIdx = workshopModPath.indexOf(driveCMarker)
                                    val steamappsMarker = "/steamapps/common/"
                                    val steamappsIdx = workshopModPath.indexOf(steamappsMarker)
                                    when {
                                        // Wine path: /.../.wine/drive_c/users/... → C:\users\...
                                        driveCIdx >= 0 -> {
                                            "C:\\" + workshopModPath.substring(driveCIdx + driveCMarker.length)
                                                .replace('/', '\\')
                                        }
                                        // Game directory: /.../steamapps/common/GameName/... → Game\subfolder
                                        steamappsIdx >= 0 -> {
                                            workshopModPath.substring(steamappsIdx + steamappsMarker.length)
                                                .replace('/', '\\')
                                        }
                                        // Fallback: show full path
                                        else -> workshopModPath
                                    }
                                } else {
                                    "Automatic"
                                }
                                Text(
                                    text = modFolderDisplay,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (workshopModPath.isNotEmpty()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }

                            filteredItems.forEach { item ->
                                val checked = selectedIds[item.publishedFileId] ?: false

                                ListItem(
                                    leadingContent = {
                                        if (item.previewUrl.isNotBlank()) {
                                            CoilImage(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(4.dp)),
                                                imageModel = { item.previewUrl },
                                                imageOptions = ImageOptions(
                                                    contentScale = ContentScale.Crop
                                                ),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = item.title.take(1).uppercase(),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    headlineContent = {
                                        Column {
                                            Text(text = item.title)
                                            if (item.fileSizeBytes > 0) {
                                                Text(
                                                    text = StorageUtils.formatBinarySize(item.fileSizeBytes),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                )
                                            }
                                            if (item.timeUpdated > 0) {
                                                val dateStr = remember(item.timeUpdated) {
                                                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                                                        .format(Date(item.timeUpdated * 1000L))
                                                }
                                                Text(
                                                    text = "Updated $dateStr",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { isChecked ->
                                                selectedIds[item.publishedFileId] = isChecked
                                            }
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        selectedIds[item.publishedFileId] = !checked
                                    }
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }

                // Save button
                if (!isLoading && !fetchFailed) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val selectedCount = selectedIds.count { it.value }
                        val totalSelectedSize = workshopItems
                            .filter { selectedIds[it.publishedFileId] == true }
                            .sumOf { it.fileSizeBytes }
                        val sizeText = if (totalSelectedSize > 0) {
                            " (${StorageUtils.formatBinarySize(totalSelectedSize)})"
                        } else {
                            ""
                        }
                        Text(
                            modifier = Modifier.weight(0.5f),
                            text = "$selectedCount of ${workshopItems.size} mods selected$sizeText",
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Button(
                            onClick = {
                                val enabledIds = selectedIds
                                    .filter { it.value }
                                    .keys
                                onSave(enabledIds)
                            }
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        },
    )

    if (showFolderPicker) {
        FolderPickerDialog(
            currentPath = workshopModPath,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
            onSelect = { path ->
                onModPathChanged(path)
                showFolderPicker = false
            },
            onClear = {
                onModPathChanged("")
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false },
        )
    }
}

// ── Folder Picker Dialog ──────────────────────────────────────────────────────

private data class FolderRoot(
    val label: String,
    val icon: ImageVector,
    val dir: File,
)

@Composable
private fun FolderPickerDialog(
    currentPath: String,
    gameRootDir: File?,
    winePrefix: String,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val roots = remember(gameRootDir, winePrefix) {
        buildList {
            if (gameRootDir?.isDirectory == true) {
                add(FolderRoot("Game Directory", Icons.Default.SportsEsports, gameRootDir))
            }
            if (winePrefix.isNotEmpty()) {
                val usersDir = File(winePrefix, "drive_c/users")
                val steamuser = File(usersDir, "steamuser")
                val userDir = if (steamuser.isDirectory) {
                    steamuser
                } else if (usersDir.isDirectory) {
                    usersDir.listFiles()
                        ?.firstOrNull { it.isDirectory && !it.name.equals("Public", ignoreCase = true) }
                        ?: steamuser
                } else {
                    steamuser
                }
                val driveC = File(winePrefix, "drive_c")
                if (driveC.isDirectory) add(FolderRoot("C: Drive", Icons.Default.Computer, driveC))
                val docs = File(userDir, "Documents")
                if (docs.isDirectory) add(FolderRoot("My Documents", Icons.Default.Description, docs))
                val myGames = File(userDir, "Documents/My Games")
                if (myGames.isDirectory) add(FolderRoot("My Games", Icons.Default.Gamepad, myGames))
                val roaming = File(userDir, "AppData/Roaming")
                if (roaming.isDirectory) add(FolderRoot("AppData / Roaming", Icons.Default.Settings, roaming))
                val local = File(userDir, "AppData/Local")
                if (local.isDirectory) add(FolderRoot("AppData / Local", Icons.Default.SnippetFolder, local))
                val localLow = File(userDir, "AppData/LocalLow")
                if (localLow.isDirectory) add(FolderRoot("AppData / LocalLow", Icons.Default.Inventory2, localLow))
            }
        }
    }

    var currentDir by remember { mutableStateOf<File?>(null) }
    var currentRootLabel by remember { mutableStateOf("") }
    var subDirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(currentDir) {
        val dir = currentDir
        if (dir != null && dir.isDirectory) {
            loading = true
            try {
                subDirs = withContext(Dispatchers.IO) {
                    dir.listFiles()
                        ?.filter { it.isDirectory && !it.name.startsWith(".") }
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                subDirs = emptyList()
            } finally {
                loading = false
            }
        } else {
            subDirs = emptyList()
        }
    }

    // Breadcrumb path relative to the root
    val breadcrumb = remember(currentDir, currentRootLabel) {
        val dir = currentDir ?: return@remember ""
        val rootDir = roots.find { it.label == currentRootLabel }?.dir ?: return@remember dir.name
        val rel = dir.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/', '\\')
        val segments = rel.split(Regex("[/\\\\]+")).filterNot { it.isBlank() }
        if (segments.isEmpty()) currentRootLabel else "$currentRootLabel / ${segments.joinToString(" / ")}"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(480.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Header ──────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                ) {
                    Column {
                        Text(
                            text = if (currentDir == null) "Select Mod Folder" else "Browse",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (currentPath.isNotEmpty() && currentDir == null) {
                            val currentFolderName = currentPath
                                .substringAfterLast('/').substringAfterLast('\\')
                                .ifEmpty { "—" }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = currentFolderName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (currentDir != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = breadcrumb,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // ── Back navigation bar (only when browsing) ────────
                if (currentDir != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    val parent = currentDir?.parentFile
                                    val rootDir = roots
                                        .find { it.label == currentRootLabel }
                                        ?.dir
                                    if (parent != null && rootDir != null &&
                                        parent.absolutePath.startsWith(rootDir.absolutePath)
                                    ) {
                                        currentDir = parent
                                    } else {
                                        currentDir = null
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = currentDir?.name ?: "",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                }

                // ── Content ─────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (currentDir == null) {
                        // Root location cards
                        Spacer(Modifier.height(8.dp))
                        roots.forEach { root ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            currentDir = root.dir
                                            currentRootLabel = root.label
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(10.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            root.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        text = root.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    )
                                }
                            }
                        }
                        if (roots.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "No browsable locations found.",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    } else {
                        // Sub-directory listing
                        if (loading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp,
                                )
                            }
                        } else if (subDirs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.FolderOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "No subdirectories",
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.height(4.dp))
                            subDirs.forEach { dir ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentDir = dir }
                                        .padding(horizontal = 24.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        text = dir.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                // ── Footer ──────────────────────────────────────────
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentPath.isNotEmpty()) {
                        TextButton(onClick = onClear) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    if (currentDir != null) {
                        Button(
                            onClick = {
                                currentDir?.absolutePath?.let { onSelect(it) }
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text("Select")
                        }
                    }
                }
            }
        }
    }
}
