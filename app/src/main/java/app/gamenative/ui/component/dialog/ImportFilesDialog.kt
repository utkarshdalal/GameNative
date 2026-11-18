package app.gamenative.ui.component.dialog

import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerFileImporter
import app.gamenative.utils.FormatUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportFilesDialog(
    visible: Boolean,
    gameName: String,
    gameRootDir: File?,
    winePrefix: String,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browser = rememberContainerFolderBrowserState(gameRootDir, winePrefix)
    val root = browser.root
    val folder = browser.folder

    val selectedFiles = remember { mutableStateListOf<ContainerFileImporter.PendingFile>() }
    var replaceExisting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedNames by remember { mutableStateOf<List<String>>(emptyList()) }

    var importJob by remember { mutableStateOf<Job?>(null) }
    var importCount by remember { mutableIntStateOf(0) }
    var progressIndex by remember { mutableIntStateOf(0) }
    var progressFileName by remember { mutableStateOf("") }
    var progressBytes by remember { mutableLongStateOf(0L) }
    var progressTotal by remember { mutableLongStateOf(-1L) }
    val importing = importJob != null

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val described = withContext(Dispatchers.IO) { ContainerFileImporter.describe(context, uris) }
            described.forEach { file ->
                if (selectedFiles.none { it.uri == file.uri }) selectedFiles.add(file)
            }
        }
    }

    val dismiss = {
        if (!importing) onDismissRequest()
    }

    fun startImport() {
        val destRoot = root ?: return
        val destDir = folder ?: return
        if (selectedFiles.isEmpty()) return
        errorMessage = null
        failedNames = emptyList()
        if (!ContainerFileImporter.ensureInside(destRoot.dir, destDir) || !destDir.isDirectory) return
        val needed = selectedFiles.filter { it.size > 0 }.sumOf { it.size }
        val available = runCatching { StatFs(destDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (needed > available) {
            errorMessage = context.getString(
                R.string.import_files_not_enough_space,
                FormatUtils.formatBytes(needed),
                FormatUtils.formatBytes(available),
            )
            return
        }
        val files = selectedFiles.toList()
        importCount = files.size
        val displayPath = browser.displayPath(destDir)
        progressIndex = 0
        progressFileName = ""
        progressBytes = 0L
        progressTotal = -1L
        importJob = scope.launch {
            try {
                val result = ContainerFileImporter.import(context, files, destDir, replaceExisting) { index, name, copied, total ->
                    progressIndex = index
                    progressFileName = name
                    progressBytes = copied
                    progressTotal = total
                }
                val parts = mutableListOf(context.getString(R.string.import_files_result, result.copied, displayPath))
                if (result.skipped > 0) parts.add(context.getString(R.string.import_files_result_skipped, result.skipped))
                if (result.failed.isNotEmpty()) parts.add(context.getString(R.string.container_files_result_failed, result.failed.size))
                SnackbarManager.show(parts.joinToString(". "))
                failedNames = result.failed
                selectedFiles.clear()
            } catch (e: CancellationException) {
                SnackbarManager.show(context.getString(R.string.import_files_cancelled))
                throw e
            } finally {
                importJob = null
                browser.refresh()
            }
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ContainerFilesDialogHeader(
                    icon = Icons.Default.UploadFile,
                    title = stringResource(R.string.import_files_title),
                    gameName = gameName,
                    closeEnabled = !importing,
                    onClose = dismiss,
                )

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    ContainerFilesSection(
                        icon = Icons.Default.UploadFile,
                        title = stringResource(R.string.import_files_step_choose_files),
                        description = stringResource(R.string.import_files_pick_hint),
                    ) {
                        Button(
                            onClick = { pickerLauncher.launch(arrayOf("*/*")) },
                            enabled = !importing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.import_files_select))
                        }
                        if (selectedFiles.isNotEmpty()) {
                            Column {
                                val files = selectedFiles.toList()
                                files.forEachIndexed { index, file ->
                                    ListItem(
                                        headlineContent = {
                                            Column {
                                                Text(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (file.size >= 0) {
                                                    Text(
                                                        text = FormatUtils.formatBytes(file.size),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        },
                                        trailingContent = {
                                            IconButton(
                                                onClick = { selectedFiles.remove(file) },
                                                enabled = !importing,
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = stringResource(R.string.import_files_remove),
                                                )
                                            }
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    )
                                    if (index < files.lastIndex) ContainerFilesDivider()
                                }
                            }
                        }
                    }

                    ContainerFilesSection(
                        icon = Icons.Default.Folder,
                        title = stringResource(R.string.import_files_step_choose_destination),
                        description = stringResource(R.string.import_files_destination_hint),
                    ) {
                        ContainerFolderBrowser(
                            state = browser,
                            enabled = !importing,
                            noContainerText = stringResource(R.string.import_files_no_container),
                            showNewFolderControl = true,
                            onCreateFolderResult = { success ->
                                errorMessage = if (success) null else context.getString(R.string.container_files_create_folder_failed)
                            },
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !importing) { replaceExisting = !replaceExisting },
                    ) {
                        Checkbox(
                            checked = replaceExisting,
                            onCheckedChange = { replaceExisting = it },
                            enabled = !importing,
                        )
                        Text(stringResource(R.string.import_files_replace_existing))
                    }

                    if (importing) {
                        val fileCount = importCount.coerceAtLeast(1)
                        val fileFraction = if (progressTotal > 0) {
                            (progressBytes.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        ContainerFilesProgressRow(
                            progressText = stringResource(R.string.import_files_progress, progressIndex + 1, fileCount),
                            progress = (progressIndex + fileFraction) / fileCount,
                            fileName = progressFileName,
                            onCancel = { importJob?.cancel() },
                        )
                    } else {
                        val destination = folder
                        val summary = when {
                            selectedFiles.isEmpty() -> stringResource(R.string.container_files_none_selected)
                            destination != null -> stringResource(
                                R.string.import_files_copy_summary,
                                selectedFiles.size,
                                browser.displayPath(destination),
                            )
                            else -> stringResource(
                                R.string.import_files_selection_summary,
                                selectedFiles.size,
                                FormatUtils.formatBytes(selectedFiles.filter { it.size > 0 }.sumOf { it.size }),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = { startImport() },
                                enabled = selectedFiles.isNotEmpty() && destination != null,
                            ) {
                                Text(stringResource(R.string.import_files_copy_button, selectedFiles.size))
                            }
                        }
                    }

                    errorMessage?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                    if (failedNames.isNotEmpty()) {
                        Column {
                            Text(
                                text = stringResource(R.string.import_files_failed_header),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            failedNames.forEach { name ->
                                Text(
                                    text = name,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
