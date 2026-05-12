package app.gamenative.ui.screen.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import app.gamenative.R
import app.gamenative.ui.theme.settingsTileColors
import com.winlator.contents.AdrenotoolsManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.settings.SettingsListDropdownSearchable
import app.gamenative.utils.Net
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverManagerDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    var isImporting by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    var driverManifest by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoadingManifest by remember { mutableStateOf(true) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var selectedDriverKey by remember { mutableStateOf("") }

    val installedDrivers = remember { mutableStateListOf<String>() }
    val driverMeta = remember { mutableStateMapOf<String, Pair<String, String>>() }
    var driverToDelete by remember { mutableStateOf<String?>(null) }

    val refreshDriverList: () -> Unit = {
        installedDrivers.clear()
        driverMeta.clear()
        try {
            val list = AdrenotoolsManager(ctx).enumarateInstalledDrivers()
            installedDrivers.addAll(list)
            val mgr = AdrenotoolsManager(ctx)
            list.forEach { id ->
                driverMeta[id] = mgr.getDriverName(id) to mgr.getDriverVersion(id)
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) {
        refreshDriverList()
        scope.launch(Dispatchers.IO) {
            try {
                val manifestUrl = "https://raw.githubusercontent.com/utkarshdalal/gamenative-landing-page/refs/heads/main/data/manifest.json"
                val request = Request.Builder().url(manifestUrl).build()
                val response = Net.http.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonString = response.body?.string() ?: "{}"
                    val jsonObject = Json.decodeFromString<JsonObject>(jsonString)
                    val manifest = jsonObject.entries.associate { it.key to it.value.toString().trim('"') }
                    withContext(Dispatchers.Main) {
                        driverManifest = manifest
                        isLoadingManifest = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        manifestError = ctx.getString(R.string.driver_error_manifest, response.code)
                        isLoadingManifest = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    manifestError = ctx.getString(R.string.driver_error_loading, e.message ?: "")
                    isLoadingManifest = false
                }
            }
        }
    }

    LoadingDialog(visible = isDownloading, progress = downloadProgress, message = stringResource(R.string.downloading))

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                isImporting = true
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, it) }
                if (res.startsWith("Installed driver:")) refreshDriverList()
                SnackbarManager.show(res)
                isImporting = false
            }
        }
    }

    val downloadAndInstallDriver = { driverFileName: String ->
        scope.launch {
            isDownloading = true
            try {
                val destFile = File(ctx.cacheDir, driverFileName)
                SteamService.fetchFileWithFallback(fileName = "drivers/$driverFileName", dest = destFile, context = ctx) { progress ->
                    scope.launch(Dispatchers.Main) { downloadProgress = progress.coerceIn(0f, 1f) }
                }
                isDownloading = false
                isInstalling = true
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, Uri.fromFile(destFile)) }
                if (res.startsWith("Installed driver:")) refreshDriverList()
                SnackbarManager.show(res)
                destFile.delete()
            } catch (e: Exception) {
                SnackbarManager.show("Error: ${e.message}")
            } finally {
                isDownloading = false
                isInstalling = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.driver_manager), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text = "Import a custom graphics driver package", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))

                if (isLoadingManifest) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(text = "Loading available drivers...", modifier = Modifier.weight(1f))
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    manifestError?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    }

                    if (driverManifest.isNotEmpty()) {
                        Text(text = "Available online drivers:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

                        val manifestKeys = remember(driverManifest) { driverManifest.keys.toList().sorted() }

                        val shape = RoundedCornerShape(4.dp)
                        // HIER: Höhe auf 48.dp (ca. die Hälfte der Standardhöhe) reduziert
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .height(48.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                                .clip(shape),
                            contentAlignment = Alignment.CenterStart
                        ) {

                            SettingsListDropdownSearchable(
                                items = manifestKeys,
                                value = manifestKeys.indexOf(selectedDriverKey),
                                onItemSelected = { index -> selectedDriverKey = manifestKeys[index] },
                                title = {},
                                fallbackDisplay = stringResource(R.string.select_a_driver),
                                colors = settingsTileColors()
                            )
                        }

                        if (selectedDriverKey.isNotEmpty()) {
                            Button(
                                onClick = { downloadAndInstallDriver(driverManifest[selectedDriverKey]!!) },
                                enabled = !isDownloading && !isImporting,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(stringResource(R.string.download))
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                Text(text = "Import from local storage:", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { launcher.launch(arrayOf("application/zip")) },
                    enabled = !isImporting && !isDownloading,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.import_zip_from_device))
                }

                if (installedDrivers.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(text = "Installed custom drivers", style = MaterialTheme.typography.titleMedium)
                    installedDrivers.forEach { id ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = driverMeta[id]?.first ?: id, modifier = Modifier.weight(1f))
                            IconButton(onClick = { driverToDelete = id }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    driverToDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { driverToDelete = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.remove_driver_confirmation, id)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        AdrenotoolsManager(ctx).removeDriver(id)
                        refreshDriverList()
                    } catch (e: Exception) { SnackbarManager.show("Error: ${e.message}") }
                    driverToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { driverToDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun handlePickedUri(context: Context, uri: Uri): String {
    return try {
        val name = AdrenotoolsManager(context).installDriver(uri)
        if (name.isNotEmpty()) "Installed driver: $name" else "Failed to install"
    } catch (e: Exception) { "Error: ${e.message}" }
}
