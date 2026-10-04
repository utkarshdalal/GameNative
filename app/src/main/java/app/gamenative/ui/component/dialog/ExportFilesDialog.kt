package app.gamenative.ui.component.dialog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerFileExporter
import app.gamenative.utils.FormatUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ExportFilesDialog(
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

    val selectedFiles = remember { mutableStateMapOf<File, Long>() }
    var failedNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var permissionDenied by remember { mutableStateOf(false) }
    val destinationLabel = remember(gameName) { ContainerFileExporter.destinationLabel(gameName) }

    var exportJob by remember { mutableStateOf<Job?>(null) }
    var exportCount by remember { mutableIntStateOf(0) }
    var progressIndex by remember { mutableIntStateOf(0) }
    var progressFileName by remember { mutableStateOf("") }
    var progressBytes by remember { mutableLongStateOf(0L) }
    var progressTotal by remember { mutableLongStateOf(-1L) }
    val exporting = exportJob != null

    fun startExport() {
        if (selectedFiles.isEmpty()) return
        failedNames = emptyList()
        permissionDenied = false
        val files = selectedFiles.keys.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        exportCount = files.size
        progressIndex = 0
        progressFileName = ""
        progressBytes = 0L
        progressTotal = -1L
        exportJob = scope.launch {
            try {
                val result = ContainerFileExporter.export(context, files, gameName) { index, name, copied, total ->
                    progressIndex = index
                    progressFileName = name
                    progressBytes = copied
                    progressTotal = total
                }
                val parts = mutableListOf(context.getString(R.string.export_files_saved_result, result.copied, result.destinationLabel))
                if (result.failed.isNotEmpty()) parts.add(context.getString(R.string.container_files_result_failed, result.failed.size))
                SnackbarManager.show(parts.joinToString(". "))
                failedNames = result.failed
                selectedFiles.clear()
            } catch (e: CancellationException) {
                SnackbarManager.show(context.getString(R.string.export_files_cancelled))
                throw e
            } finally {
                exportJob = null
                browser.refresh()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startExport()
        } else {
            permissionDenied = true
        }
    }

    val requestExport = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startExport()
        }
    }

    val dismiss = {
        if (!exporting) onDismissRequest()
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
                    icon = Icons.Default.FileDownload,
                    title = stringResource(R.string.export_files_title),
                    gameName = gameName,
                    closeEnabled = !exporting,
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
                        icon = Icons.Default.FileDownload,
                        title = stringResource(R.string.export_files_source),
                        description = stringResource(R.string.export_files_source_hint),
                    ) {
                        ContainerFolderBrowser(
                            state = browser,
                            enabled = !exporting,
                            noContainerText = stringResource(R.string.export_files_no_container),
                            showFiles = true,
                            selectedFiles = selectedFiles.keys,
                            onToggleFile = { file, size ->
                                if (selectedFiles.containsKey(file)) {
                                    selectedFiles.remove(file)
                                } else {
                                    selectedFiles[file] = size
                                }
                            },
                        )
                    }

                    if (exporting) {
                        val fileCount = exportCount.coerceAtLeast(1)
                        val fileFraction = if (progressTotal > 0) {
                            (progressBytes.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        ContainerFilesProgressRow(
                            progressText = stringResource(R.string.export_files_progress, progressIndex + 1, fileCount),
                            progress = (progressIndex + fileFraction) / fileCount,
                            fileName = progressFileName,
                            onCancel = { exportJob?.cancel() },
                        )
                    } else {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (selectedFiles.isEmpty()) {
                                        stringResource(R.string.container_files_none_selected)
                                    } else {
                                        stringResource(
                                            R.string.export_files_selection_summary,
                                            selectedFiles.size,
                                            FormatUtils.formatBytes(selectedFiles.values.sum()),
                                        )
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selectedFiles.isNotEmpty()) {
                                    TextButton(onClick = { selectedFiles.clear() }) {
                                        Text(stringResource(R.string.export_files_clear))
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = requestExport,
                                    enabled = selectedFiles.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.export_files_export_button, selectedFiles.size))
                                }
                            }
                            Text(
                                text = stringResource(R.string.export_files_saved_to, destinationLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (permissionDenied) {
                                Text(
                                    text = stringResource(R.string.export_files_permission_needed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (failedNames.isNotEmpty()) {
                        Column {
                            Text(
                                text = stringResource(R.string.export_files_failed_header),
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
