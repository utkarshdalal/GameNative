package app.gamenative.ui.component.dialog

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.screen.xr.windows.WindowsVrRuntimeService
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.LaunchMode
import app.gamenative.utils.SteamLaunchOptions
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
fun VrTabContent(state: ContainerConfigState, containerId: String?) {
    val config = state.config.value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Shown in the tab: a snackbar would be hidden behind the dialog.
    var exportResult by remember { mutableStateOf<String?>(null) }
    var exportedReport by remember { mutableStateOf<Uri?>(null) }
    var vrLaunchOptions by remember { mutableStateOf<Pair<String, List<app.gamenative.data.LaunchInfo>>?>(null) }
    LaunchedEffect(containerId) {
        vrLaunchOptions = withContext(Dispatchers.IO) {
            val id = containerId ?: return@withContext null
            if (ContainerUtils.extractGameSourceFromContainerId(id) != app.gamenative.data.GameSource.STEAM) return@withContext null
            val gameId = ContainerUtils.extractGameIdFromContainerId(id)
            val name = app.gamenative.service.SteamService.getAppInfoOf(gameId)?.name.orEmpty()
            name to SteamLaunchOptions.candidates(gameId, LaunchMode.VR)
        }
    }
    val exportResultRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(exportResult) { if (exportResult != null) exportResultRequester.bringIntoView() }
    SettingsGroup() {
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.xr_windows_vr_toggle)) },
            subtitle = { Text(text = stringResource(R.string.xr_windows_vr_toggle_desc)) },
            state = config.windowsVrEnabled,
            onCheckedChange = { checked ->
                state.config.value = config.copy(windowsVrEnabled = checked)
            },
        )
        if (config.windowsVrEnabled) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.xr_open_composite_toggle)) },
                subtitle = { Text(text = stringResource(R.string.xr_open_composite_toggle_desc)) },
                state = config.openCompositeEnabled,
                onCheckedChange = { checked ->
                    state.config.value = config.copy(openCompositeEnabled = checked)
                },
            )
        }
        vrLaunchOptions?.let { (gameName, options) ->
            if (options.size > 1) {
                LaunchOptionSetting(
                    title = stringResource(R.string.vr_launch_option),
                    options = options,
                    gameName = gameName,
                    selectedKey = config.vrLaunchOption,
                    onSelected = { key -> state.config.value = config.copy(vrLaunchOption = key) },
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = stringResource(R.string.xr_render_scale))
            Slider(
                value = config.xrRenderScale.toFloat(),
                onValueChange = { newValue ->
                    val stepped = ((newValue.roundToInt() + 2) / 5 * 5).coerceIn(25, 100)
                    state.config.value = config.copy(xrRenderScale = stepped)
                },
                valueRange = 25f..100f,
            )
            Text(text = "${config.xrRenderScale}%")
        }
        val xrRates = listOf(72, 90, 120)
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.xr_refresh_rate)) },
            value = xrRates.indexOf(config.xrRefreshRate).coerceAtLeast(0),
            items = xrRates.map { "$it Hz" },
            onItemSelected = { idx ->
                state.config.value = config.copy(xrRefreshRate = xrRates[idx])
            },
        )
        Text(
            text = stringResource(R.string.immersive_windows_vr_restart_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.immersive_windows_vr_export)) },
            subtitle = { Text(text = stringResource(R.string.vr_diagnostics_export_desc)) },
            onClick = {
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            val container = containerId?.let { id ->
                                runCatching { ContainerUtils.getContainer(context, id) }.getOrNull()
                            }
                            saveToDownloads(context, WindowsVrRuntimeService.exportLastDiagnostics(context, container))
                        }.onFailure { Timber.w(it, "Windows VR diagnostics export failed") }.getOrNull()
                    }
                    exportedReport = saved?.second
                    exportResult = if (saved != null) {
                        context.getString(R.string.vr_diagnostics_saved, saved.first)
                    } else {
                        context.getString(R.string.vr_diagnostics_export_failed)
                    }
                }
            },
        )
        Column(modifier = Modifier.bringIntoViewRequester(exportResultRequester)) {
            exportResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            exportedReport?.let { report ->
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { shareReport(context, report) }) {
                        Text(text = stringResource(R.string.vr_diagnostics_share))
                    }
                    TextButton(onClick = { openReportFolder(context) }) {
                        Text(text = stringResource(R.string.vr_diagnostics_open_folder))
                    }
                }
            }
        }
    }
}

private fun shareReport(context: Context, report: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, report)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, context.getString(R.string.vr_diagnostics_share))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }.onFailure { Timber.w(it, "No app to share the VR report") }
}

// Android can't preselect a file; falls back to Downloads if no app opens folder URIs.
private fun openReportFolder(context: Context) {
    val folder = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:${Environment.DIRECTORY_DOWNLOADS}/GameNative",
    )
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Timber.w(it, "No app to open Downloads") }
    }
}

private fun saveToDownloads(context: Context, report: File): Pair<String, Uri?> {
    val folder = "${Environment.DIRECTORY_DOWNLOADS}/GameNative"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, report.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { output -> report.inputStream().use { it.copyTo(output) } }
        return "$folder/${report.name}" to uri
    }
    val directory = File(Environment.getExternalStorageDirectory(), folder).apply { mkdirs() }
    report.copyTo(File(directory, report.name), overwrite = true)
    return "$folder/${report.name}" to null
}
