package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import app.gamenative.ui.component.NoExtractOutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopManagerDialog(
    visible: Boolean,
    currentEnabledIds: Set<Long>,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    onSave: (Set<Long>) -> Unit,
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

    val allSelected by remember(selectedIds.toMap()) {
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

                            // Select All toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
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
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
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
                                color = Color.White.copy(alpha = 0.7f)
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
            }
        },
    )
}
