package app.gamenative.ui.screen.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.gamenative.MainActivity
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.theme.ThemeManager
import app.gamenative.theme.ThemeManager.ThemeEntry
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelectorScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Ensure ThemeManager is initialized
    LaunchedEffect(Unit) {
        try { ThemeManager.init(context) } catch (_: Throwable) { }
    }

    val themes by ThemeManager.availableThemes.collectAsState()
    val selectedId by ThemeManager.selectedThemeId.collectAsState(null)
    
    // Check for pending toast on screen initialization (after app restart)
    LaunchedEffect(Unit) {
        PrefManager.pendingThemeAddedToast?.let { themeName ->
            Toast.makeText(
                context,
                context.getString(R.string.theme_added_toast, themeName),
                Toast.LENGTH_SHORT
            ).show()
            PrefManager.pendingThemeAddedToast = null
        }
    }
    
    // State for fullscreen preview dialog
    var previewDialogImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewDialogTitle by remember { mutableStateOf("") }
    
    // State for remove confirmation dialog
    var themeToRemove by remember { mutableStateOf<ThemeEntry?>(null) }
    
    // State for add theme info dialog
    var showAddThemeDialog by remember { mutableStateOf(false) }
    var dontShowAgain by remember { mutableStateOf(false) }

    // Folder picker launcher for adding external themes
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Convert content URI to file path
                val folderPath = getPathFromUri(context, uri)
                if (folderPath == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.theme_validation_failed, "Could not access folder"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                // Validate the theme
                val validation = ThemeManager.validateThemeFolder(folderPath)
                when (validation) {
                    is ThemeManager.ThemeValidationResult.Valid -> {
                        // Store theme name for toast (in case app restarts during folder selection)
                        PrefManager.pendingThemeAddedToast = validation.manifest.title
                        
                        // Add the theme
                        val entry = ThemeManager.addExternalTheme(folderPath)
                        withContext(Dispatchers.Main) {
                            if (entry != null) {
                                // Clear pending toast and show immediately since we didn't restart
                                PrefManager.pendingThemeAddedToast = null
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.theme_added_toast, entry.name),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    is ThemeManager.ThemeValidationResult.Invalid -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.theme_validation_failed, validation.error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error adding external theme")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.theme_validation_failed, e.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Handle opening folder picker (with dialog check)
    val onAddThemeClick = {
        if (PrefManager.showAddThemeDialog) {
            showAddThemeDialog = true
        } else {
            folderPickerLauncher.launch(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.theme_selector_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onAddThemeClick() }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.theme_add_external))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = Modifier.statusBarsPadding(),
    ) { paddingValues ->
        // Get orientation from MainActivity's tracked state (updates on config change)
        // This is needed because android:configChanges prevents LocalConfiguration from updating
        val orientation by MainActivity.currentOrientation
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            itemsIndexed(
                items = themes,
                // Key on both theme id and orientation to force recomposition on rotation
                key = { _, entry -> "${entry.id}_$orientation" }
            ) { _, entry ->
                ThemeCard(
                    entry = entry,
                    isSelected = entry.id == selectedId,
                    onActivate = {
                        ThemeManager.selectTheme(entry.id)
                    },
                    onPreviewClick = { image ->
                        previewDialogImage = image
                        previewDialogTitle = entry.name
                    },
                    onRemove = if (entry.source == ThemeManager.Source.External) {
                        { themeToRemove = entry }
                    } else null,
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
    
    // Fullscreen preview dialog
    if (previewDialogImage != null) {
        PreviewImageDialog(
            image = previewDialogImage!!,
            title = previewDialogTitle,
            onDismiss = { previewDialogImage = null },
        )
    }
    
    // Remove confirmation dialog
    themeToRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { themeToRemove = null },
            title = { Text(stringResource(R.string.theme_remove_title)) },
            text = { 
                Text(stringResource(R.string.theme_remove_message, entry.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = entry.name
                        ThemeManager.removeExternalTheme(entry.id)
                        themeToRemove = null
                        Toast.makeText(
                            context,
                            context.getString(R.string.theme_removed_toast, name),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.theme_remove_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { themeToRemove = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Add theme info dialog
    if (showAddThemeDialog) {
        AlertDialog(
            onDismissRequest = { showAddThemeDialog = false },
            title = { Text(stringResource(R.string.theme_add_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.theme_add_dialog_message),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.theme_add_dont_show_again),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dontShowAgain) {
                            PrefManager.showAddThemeDialog = false
                        }
                        showAddThemeDialog = false
                        folderPickerLauncher.launch(null)
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddThemeDialog = false }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ThemeCard(
    entry: ThemeEntry,
    isSelected: Boolean,
    onActivate: () -> Unit,
    onPreviewClick: (ImageBitmap?) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // Use MainActivity's tracked orientation state (updates on config change)
    // This is needed because android:configChanges prevents LocalConfiguration from updating
    val orientation by MainActivity.currentOrientation
    val screenWidthDp by MainActivity.currentScreenWidthDp
    val configChangeCounter by MainActivity.configurationChangeCounter
    
    // Use both orientation check and dimension comparison for reliability
    val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE ||
            screenWidthDp > LocalConfiguration.current.screenHeightDp
    
    // Key on both entry.id and config change counter to ensure proper recomposition on rotation
    var previewImage by remember(entry.id, configChangeCounter) { mutableStateOf<ImageBitmap?>(null) }
    
    // Focus state for controller navigation highlight
    var isFocused by remember { mutableStateOf(false) }
    
    // For scrolling the focused item into view
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Load preview image asynchronously, also reload on orientation change
    LaunchedEffect(entry, configChangeCounter) {
        previewImage = loadThemePreviewImage(context, entry)
    }

    // Gradient brush for focus highlight (matches theme engine default)
    val focusBorderBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.primary,
        )
    )

    // Track the item's size for scrolling with margin
    var itemHeight by remember { mutableStateOf(0f) }
    
    // Wrap card in a Box to handle focus border properly
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onGloballyPositioned { coordinates ->
                itemHeight = coordinates.size.height.toFloat()
            }
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (focusState.isFocused) {
                    // Scroll into view with extra margin below
                    coroutineScope.launch {
                        // Request a rect that includes 60px extra space below the item
                        val extraMargin = 60f
                        bringIntoViewRequester.bringIntoView(
                            Rect(0f, 0f, 0f, itemHeight + extraMargin)
                        )
                    }
                }
            }
            .focusable()
            // Handle controller primary button (A/Enter) to activate theme
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            onActivate()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .clickable { onActivate() }
            .then(
                if (isFocused) {
                    Modifier.border(4.dp, focusBorderBrush, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            )
    ) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        if (isLandscape) {
            // Landscape: horizontal layout - preview on left, content on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                // Square preview image (clickable for fullscreen)
                ThemePreviewImage(
                    previewImage = previewImage,
                    entryName = entry.name,
                    onClick = { onPreviewClick(previewImage) },
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
                )
                
                // Content area with activate button
                ThemeCardContent(
                    entry = entry,
                    isSelected = isSelected,
                    onActivate = onActivate,
                    onRemove = onRemove,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp),
                )
            }
        } else {
            // Portrait: vertical layout - square preview on top, content below
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Square preview image (clickable for fullscreen)
                ThemePreviewImage(
                    previewImage = previewImage,
                    entryName = entry.name,
                    onClick = { onPreviewClick(previewImage) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                )
                
                // Content area with activate button
                ThemeCardContent(
                    entry = entry,
                    isSelected = isSelected,
                    onActivate = onActivate,
                    onRemove = onRemove,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .padding(16.dp),
                )
            }
            }
        }
    }
}

@Composable
private fun ThemePreviewImage(
    previewImage: ImageBitmap?,
    entryName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.DarkGray)
            .clickable(enabled = previewImage != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (previewImage != null) {
            Image(
                bitmap = previewImage,
                contentDescription = entryName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Default placeholder
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.Gray,
            )
        }
    }
}

@Composable
private fun ThemeCardContent(
    entry: ThemeEntry,
    isSelected: Boolean,
    onActivate: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        // Title and source badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                },
            ) {
                Text(
                    text = when (entry.source) {
                        ThemeManager.Source.BuiltIn -> stringResource(R.string.settings_theme_source_builtin)
                        ThemeManager.Source.User -> stringResource(R.string.settings_theme_source_user)
                        ThemeManager.Source.External -> stringResource(R.string.theme_source_external)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // Remove button for external themes
            if (onRemove != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.theme_remove),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Description
        val description = entry.manifest.description
            ?: stringResource(R.string.theme_no_description)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Bottom row: Author on left, Activate button on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Author (if available)
            entry.manifest.author?.let { author ->
                Text(
                    text = stringResource(R.string.theme_by_author, author),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    },
                )
            } ?: Spacer(modifier = Modifier.width(1.dp))
            
            // Activate button or Active status
            if (isSelected) {
                // Show "Active" status label (not interactive)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.theme_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                // Show "Activate" button when not selected
                Button(
                    onClick = onActivate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.theme_activate))
                }
            }
        }
    }
}

@Composable
private fun PreviewImageDialog(
    image: ImageBitmap,
    title: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(onClick = onDismiss)
                .statusBarsPadding(),
        ) {
            // Close button in top-right corner
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            
            // Image fills available height, maintains aspect ratio
            Image(
                bitmap = image,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 72.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
                contentScale = ContentScale.Fit,
            )
            
            // Title at bottom
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            )
        }
    }
}

/**
 * Load the theme preview image from the path specified in the manifest.
 * Returns null if no preview is defined or if loading fails.
 */
private suspend fun loadThemePreviewImage(context: Context, entry: ThemeEntry): ImageBitmap? {
    // Check if a preview image is defined in the manifest
    val previewPath = entry.manifest.previewImage ?: return null

    return withContext(Dispatchers.IO) {
        try {
            val assetPath = ThemeManager.getThemeAssetPath(entry)
            // Preview path in manifest is relative (e.g., "/assets/theme.png"), combine with theme root
            val imagePath = if (previewPath.startsWith("/")) {
                "$assetPath$previewPath"
            } else {
                "$assetPath/$previewPath"
            }

            when (entry.source) {
                ThemeManager.Source.BuiltIn -> {
                    context.assets.open(imagePath).use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)?.asImageBitmap()
                    }
                }
                ThemeManager.Source.User, ThemeManager.Source.External -> {
                    // For user/external themes, load from file system
                    val file = java.io.File(imagePath)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    } else {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Get the file system path from a content URI.
 * Works with SAF (Storage Access Framework) URIs from folder picker.
 */
private fun getPathFromUri(context: Context, uri: Uri): String? {
    return try {
        // Try to get the path from DocumentFile
        val docFile = DocumentFile.fromTreeUri(context, uri)
        if (docFile == null || !docFile.isDirectory) {
            Timber.w("URI is not a valid directory: %s", uri)
            return null
        }

        // For content:// URIs from SAF, we need to decode the path from the URI
        // Format: content://com.android.externalstorage.documents/tree/primary%3APath%2FTo%2FFolder
        val uriPath = uri.path ?: return null
        
        // Extract the path portion after "tree/"
        val treePath = if (uriPath.contains("/tree/")) {
            uriPath.substringAfter("/tree/")
        } else {
            uriPath
        }
        
        // Decode the path (e.g., "primary:Path/To/Folder" or "XXXX-XXXX:Path/To/Folder")
        val decodedPath = Uri.decode(treePath)
        
        // Split into storage volume and relative path
        val parts = decodedPath.split(":", limit = 2)
        if (parts.size != 2) {
            Timber.w("Could not parse URI path: %s", decodedPath)
            return null
        }
        
        val volumeId = parts[0]
        val relativePath = parts[1]
        
        // Build the full path
        val basePath = when (volumeId.lowercase()) {
            "primary" -> android.os.Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volumeId"
        }
        
        val fullPath = "$basePath/$relativePath"
        val file = java.io.File(fullPath)
        
        if (file.exists() && file.isDirectory) {
            fullPath
        } else {
            Timber.w("Path does not exist or is not a directory: %s", fullPath)
            null
        }
    } catch (e: Exception) {
        Timber.e(e, "Error getting path from URI: %s", uri)
        null
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ThemeSelectorScreen() {
    PluviaTheme {
        ThemeSelectorScreen(onBack = { })
    }
}

