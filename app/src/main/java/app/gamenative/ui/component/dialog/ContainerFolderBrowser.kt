package app.gamenative.ui.component.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.ModTargetRoot
import app.gamenative.mods.ModTargetResolver
import app.gamenative.mods.ResolvedModTargetRoot
import app.gamenative.utils.ContainerFileImporter
import app.gamenative.utils.FormatUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

private data class FolderListing(val folders: List<File>, val files: List<Pair<File, Long>>)

private val BROWSABLE_ROOTS = listOf(
    ModTargetRoot.GAME_DIR,
    ModTargetRoot.WINE_C,
    ModTargetRoot.DOCUMENTS,
    ModTargetRoot.MY_GAMES,
    ModTargetRoot.APPDATA_ROAMING,
    ModTargetRoot.APPDATA_LOCAL,
)

private fun buildContainerRoots(gameRootDir: File?, winePrefix: String): List<ResolvedModTargetRoot> {
    val resolved = runCatching { ModTargetResolver.roots(gameRootDir, winePrefix) }
        .onFailure { Timber.w(it, "Failed to resolve container roots for %s", winePrefix) }
        .getOrElse { runCatching { ModTargetResolver.roots(gameRootDir, "") }.getOrDefault(emptyList()) }
    return resolved
        .filter { it.type in BROWSABLE_ROOTS && it.dir.isDirectory }
        .sortedBy { BROWSABLE_ROOTS.indexOf(it.type) }
}

private fun iconFor(type: ModTargetRoot): ImageVector = when (type) {
    ModTargetRoot.GAME_DIR -> Icons.Default.SportsEsports
    ModTargetRoot.WINE_C -> Icons.Default.Storage
    ModTargetRoot.DOCUMENTS -> Icons.Default.Description
    ModTargetRoot.MY_GAMES -> Icons.Default.VideogameAsset
    else -> Icons.Default.Settings
}

private fun relativeWindowsPath(base: File, dir: File): String {
    val basePath = runCatching { base.canonicalPath }.getOrDefault(base.absolutePath)
    val dirPath = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
    return dirPath.removePrefix(basePath).trim(File.separatorChar).replace(File.separatorChar, '\\')
}

private fun listFolder(dir: File, includeFiles: Boolean): FolderListing {
    val entries = dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
    val folders = entries
        .filter { it.isDirectory }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    val files = if (includeFiles) {
        entries
            .filter { it.isFile }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { it to it.length() }
    } else {
        emptyList()
    }
    return FolderListing(folders, files)
}

@Stable
internal class ContainerFolderBrowserState(
    val gameRootDir: File?,
    val winePrefix: String,
) {
    val driveC = File(winePrefix, "drive_c")
    var refreshKey by mutableIntStateOf(0)
        private set
    var roots by mutableStateOf(buildContainerRoots(gameRootDir, winePrefix))
        private set
    var selectedRoot by mutableStateOf<ResolvedModTargetRoot?>(null)
    var currentDir by mutableStateOf<File?>(null)
    var showNewFolder by mutableStateOf(false)
    var newFolderName by mutableStateOf("")

    val root: ResolvedModTargetRoot?
        get() = roots.firstOrNull { it.dir == selectedRoot?.dir } ?: roots.firstOrNull()

    val folder: File?
        get() {
            val currentRoot = root
            return currentDir?.takeIf { dir ->
                currentRoot != null && dir.isDirectory && ContainerFileImporter.ensureInside(currentRoot.dir, dir)
            } ?: currentRoot?.dir
        }

    fun refresh() {
        roots = buildContainerRoots(gameRootDir, winePrefix)
        refreshKey++
    }

    fun displayPath(dir: File): String {
        if (driveC.isDirectory && ContainerFileImporter.ensureInside(driveC, dir)) {
            return "C:\\" + relativeWindowsPath(driveC, dir)
        }
        if (gameRootDir != null && ContainerFileImporter.ensureInside(gameRootDir, dir)) {
            val gameLabel = roots.firstOrNull { it.type == ModTargetRoot.GAME_DIR }?.label ?: gameRootDir.name
            val relative = relativeWindowsPath(gameRootDir, dir)
            return if (relative.isEmpty()) gameLabel else "$gameLabel\\$relative"
        }
        return dir.absolutePath
    }
}

@Composable
internal fun rememberContainerFolderBrowserState(gameRootDir: File?, winePrefix: String): ContainerFolderBrowserState =
    remember(gameRootDir, winePrefix) { ContainerFolderBrowserState(gameRootDir, winePrefix) }


@Composable
internal fun ContainerFilesDialogHeader(
    icon: ImageVector,
    title: String,
    gameName: String,
    closeEnabled: Boolean,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (gameName.isNotBlank()) {
                Text(
                    text = gameName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onClose, enabled = closeEnabled) {
            Text(stringResource(R.string.close))
        }
    }
}

@Composable
internal fun ContainerFilesSection(
    icon: ImageVector,
    title: String,
    description: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
internal fun ContainerFilesDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
    )
}

@Composable
internal fun ContainerFilesProgressRow(
    progressText: String,
    progress: Float,
    fileName: String,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = progressText, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (fileName.isNotEmpty()) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContainerFolderBrowser(
    state: ContainerFolderBrowserState,
    enabled: Boolean,
    noContainerText: String,
    showFiles: Boolean = false,
    selectedFiles: Set<File> = emptySet(),
    onToggleFile: (file: File, size: Long) -> Unit = { _, _ -> },
    showNewFolderControl: Boolean = false,
    onCreateFolderResult: (success: Boolean) -> Unit = {},
) {
    val roots = state.roots
    val root = state.root
    val folder = state.folder

    val listing by produceState(initialValue = FolderListing(emptyList(), emptyList()), folder, state.refreshKey, showFiles) {
        value = if (folder == null) {
            FolderListing(emptyList(), emptyList())
        } else {
            withContext(Dispatchers.IO) { listFolder(folder, showFiles) }
        }
    }

    if (roots.isEmpty() || root == null || folder == null) {
        Text(
            text = noContainerText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        roots.forEach { candidate ->
            ContainerRootCard(
                root = candidate,
                selected = candidate.dir == root.dir,
                enabled = enabled,
                onClick = {
                    state.selectedRoot = candidate
                    state.currentDir = candidate.dir
                    state.showNewFolder = false
                },
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = state.displayPath(folder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showNewFolderControl) {
            TextButton(
                onClick = { state.showNewFolder = !state.showNewFolder },
                enabled = enabled,
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.container_files_new_folder))
            }
        }
    }

    if (showNewFolderControl && state.showNewFolder) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.newFolderName,
                onValueChange = { state.newFolderName = it },
                label = { Text(stringResource(R.string.container_files_folder_name_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val name = ContainerFileImporter.sanitizeName(state.newFolderName)
                    val created = File(folder, name)
                    if (ContainerFileImporter.ensureInside(root.dir, created) &&
                        (created.isDirectory || created.mkdirs())
                    ) {
                        state.currentDir = created
                        state.showNewFolder = false
                        state.newFolderName = ""
                        state.refresh()
                        onCreateFolderResult(true)
                    } else {
                        onCreateFolderResult(false)
                    }
                },
                enabled = state.newFolderName.isNotBlank() && enabled,
            ) {
                Text(stringResource(R.string.container_files_create))
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = if (showFiles) 360.dp else 260.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val atRoot = runCatching { folder.canonicalPath == root.dir.canonicalPath }.getOrDefault(true)
            if (!atRoot) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(enabled = enabled) {
                                val parent = folder.parentFile
                                state.currentDir = if (parent != null && ContainerFileImporter.ensureInside(root.dir, parent)) {
                                    parent
                                } else {
                                    root.dir
                                }
                                state.showNewFolder = false
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.container_files_up),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = folder.name,
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
            if (listing.folders.isEmpty() && (!showFiles || listing.files.isEmpty())) {
                Text(
                    text = stringResource(
                        if (showFiles) R.string.container_files_empty_folder else R.string.container_files_no_subfolders,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            val files = if (showFiles) listing.files else emptyList()
            val lastIndex = listing.folders.size + files.size - 1
            listing.folders.forEachIndexed { index, sub ->
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text(text = sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    modifier = Modifier.clickable(enabled = enabled) {
                        state.currentDir = sub
                        state.showNewFolder = false
                    },
                )
                if (index < lastIndex) ContainerFilesDivider()
            }
            files.forEachIndexed { index, (file, size) ->
                val checked = file in selectedFiles
                ListItem(
                    headlineContent = {
                        Text(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = FormatUtils.formatBytes(size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleFile(file, size) },
                            enabled = enabled,
                        )
                    },
                    modifier = Modifier.clickable(enabled = enabled) { onToggleFile(file, size) },
                )
                if (listing.folders.size + index < lastIndex) ContainerFilesDivider()
            }
        }
    }
}

@Composable
private fun ContainerRootCard(
    root: ResolvedModTargetRoot,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconFor(root.type),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = root.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
