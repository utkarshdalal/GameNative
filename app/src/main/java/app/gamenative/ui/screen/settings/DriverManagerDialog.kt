package app.gamenative.ui.screen.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import app.gamenative.R
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.winlator.contents.AdrenotoolsManager
import java.io.File
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Surface
import app.gamenative.ui.theme.PluviaTheme
import android.content.res.Configuration
import android.widget.Toast
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.LoadingDialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.URL
import timber.log.Timber
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import okhttp3.Response
import java.io.FileOutputStream
import kotlinx.coroutines.delay

object Net {
    val http: OkHttpClient by lazy { OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.MILLISECONDS)     // no per-packet timer
        .pingInterval(30, TimeUnit.SECONDS)         // keep HTTP/2 alive
        .retryOnConnectionFailure(true)             // default, but explicit
        .build() }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverManagerDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    var lastMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    val scope = rememberCoroutineScope()

    // Driver manifest handling
    var driverManifest by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoadingManifest by remember { mutableStateOf(true) }
    var manifestError by remember { mutableStateOf<String?>(null) }

    // Dropdown state
    var isExpanded by remember { mutableStateOf(false) }
    var selectedDriverKey by remember { mutableStateOf("") }

    // Gather installed custom drivers via AdrenotoolsManager and allow refreshing
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
                val name = mgr.getDriverName(id)
                val version = mgr.getDriverVersion(id)
                driverMeta[id] = name to version
            }
        } catch (_: Exception) {}
    }

    // Load driver manifest from the remote URL
    LaunchedEffect(Unit) {
        refreshDriverList()

        // Fetch the driver manifest
        Timber.d("DriverManagerDialog: Fetching driver manifest...")
        scope.launch(Dispatchers.IO) {
            try {
                val manifestUrl = "https://raw.githubusercontent.com/utkarshdalal/gamenative-landing-page/refs/heads/main/data/manifest.json"
                val request = Request.Builder()
                    .url(manifestUrl)
                    .build()

                val response = Net.http.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonString = response.body?.string() ?: "{}"
                    val jsonObject = Json.decodeFromString<JsonObject>(jsonString)

                    // Convert to map of String to String
                    val manifest = jsonObject.entries.associate { it.key to it.value.toString().trim('"') }

                    withContext(Dispatchers.Main) {
                        driverManifest = manifest
                        isLoadingManifest = false
                    }
                    Timber.d("DriverManagerDialog: Manifest loaded with ${manifest.size} entries")
                } else {
                    withContext(Dispatchers.Main) {
                        manifestError = ctx.getString(R.string.driver_error_manifest, response.code)
                        isLoadingManifest = false
                    }
                    Timber.w("DriverManagerDialog: Failed to load manifest HTTP=${response.code}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    manifestError = ctx.getString(R.string.driver_error_loading, e.message ?: "")
                    isLoadingManifest = false
                }
                Timber.e(e, "DriverManagerDialog: Error loading driver manifest")
            }
        }
    }

    LoadingDialog(
        visible = isDownloading,
        progress = downloadProgress,
        message = stringResource(R.string.downloading),
    )

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isImporting = true
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, it) }
                lastMessage = res
                if (res.startsWith("Installed driver:")) refreshDriverList()
                Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()
                SteamService.isImporting = false
                isImporting = false
            }
        }
    }

    // Function to download and install a driver from URL
    val downloadAndInstallDriver = { driverFileName: String ->
        scope.launch {
            val overallStart = System.currentTimeMillis()
            isDownloading = true
            downloadProgress = 0f
            downloadBytes = 0L
            totalBytes = -1L
            try {
                Timber.d("DriverManagerDialog: Starting download drivers/$driverFileName")
                val destFile = File(ctx.cacheDir, driverFileName)
                var lastUpdate = 0L
                // Use shared downloader with automatic domain fallback and built-in .part handling
                SteamService.fetchFileWithFallback(
                    fileName = "drivers/$driverFileName",
                    dest = destFile,
                    context = ctx
                ) { progress ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 300) {
                        lastUpdate = now
                        val clamped = progress.coerceIn(0f, 1f)
                        scope.launch(Dispatchers.Main) { downloadProgress = clamped }
                    }
                }
                // Mark download complete before installing
                val downloadDurationMs = System.currentTimeMillis() - overallStart
                val downloadedSize = destFile.length()
                Timber.d("DriverManagerDialog: Download complete in ${downloadDurationMs}ms (${formatBytes(downloadedSize)})")
                withContext(Dispatchers.Main) { isDownloading = false; downloadProgress = 1f; downloadBytes = downloadedSize }

                // Install the driver from the temporary file
                withContext(Dispatchers.Main) { isInstalling = true }
                Timber.d("DriverManagerDialog: Starting install")
                val uri = Uri.fromFile(destFile)
                val installStart = System.currentTimeMillis()
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, uri) }
                val installDurationMs = System.currentTimeMillis() - installStart
                withContext(Dispatchers.Main) {
                    lastMessage = res
                    if (res.startsWith("Installed driver:")) refreshDriverList()
                    Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()
                }
                Timber.d("DriverManagerDialog: Install complete in ${installDurationMs}ms")
                Timber.d("DriverManagerDialog: Download+Install total ${(System.currentTimeMillis() - overallStart)}ms")

                // Delete the temporary file
                withContext(Dispatchers.IO) {
                    destFile.delete()
                }
            } catch (e: SocketTimeoutException) {
                val errorMessage = ctx.getString(R.string.driver_timeout)
                lastMessage = errorMessage
                Toast.makeText(ctx, errorMessage, Toast.LENGTH_SHORT).show()
                Timber.e(e, "DriverManagerDialog: Download timeout")
            } catch (e: IOException) {
                val errorMessage = if (e.message?.contains("timeout", ignoreCase = true) == true) {
                    ctx.getString(R.string.driver_timeout)
                } else {
                    ctx.getString(R.string.driver_network_error, e.message ?: "")
                }
                lastMessage = errorMessage
                Toast.makeText(ctx, errorMessage, Toast.LENGTH_SHORT).show()
                Timber.e(e, "DriverManagerDialog: Download failed with IO error")
            } catch (e: Exception) {
                val errorMessage = "Error downloading driver: ${e.message}"
                lastMessage = errorMessage
                Toast.makeText(ctx, errorMessage, Toast.LENGTH_SHORT).show()
                Timber.e(e, "DriverManagerDialog: Download failed")
            } finally {
                isDownloading = false
                isInstalling = false
                downloadProgress = 0f
                downloadBytes = 0L
                totalBytes = -1L
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
                Text(
                    text = "Import a custom graphics driver package",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Online driver selection
                if (isLoadingManifest) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Loading available drivers...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else if (manifestError != null) {
                    Text(
                        text = manifestError ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else if (driverManifest.isNotEmpty()) {
                    Text(
                        text = "Available online drivers:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = isExpanded,
                        onExpandedChange = { isExpanded = !isExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedDriverKey,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            placeholder = { Text(stringResource(R.string.select_a_driver)) }
                        )

                        ExposedDropdownMenu(
                            expanded = isExpanded,
                            onDismissRequest = { isExpanded = false }
                        ) {
                            driverManifest.keys.forEach { driverKey ->
                                DropdownMenuItem(
                                    text = { Text(driverKey) },
                                    onClick = {
                                        selectedDriverKey = driverKey
                                        isExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedDriverKey.isNotEmpty() && driverManifest.containsKey(selectedDriverKey)) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Button(
                                onClick = { downloadAndInstallDriver(driverManifest[selectedDriverKey]!!) },
                                enabled = !isDownloading && !isImporting
                            ) {
                                Text(stringResource(R.string.download))
                            }

                            if (isDownloading) {
                                if (totalBytes > 0) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        LinearProgressIndicator(progress = downloadProgress)
                                        Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                        ) {
                                            Text(
                                                text = "${formatBytes(downloadBytes)} / ${formatBytes(totalBytes)}"
                                            )
                                        }
                                    }
                                } else {
                                    Column(modifier = Modifier.weight(1f)) {
                                        LinearProgressIndicator() // indeterminate when total unknown
                                        Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                        ) {
                                            Text(text = stringResource(R.string.downloading))
                                        }
                                    }
                                }
                            }
                            if (isInstalling) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(text = stringResource(R.string.installing))
                                }
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                // Local driver import section
                Text(
                    text = "Import from local storage:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Button(
                    onClick = {
                        SteamService.isImporting = true
                        launcher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                    },
                    enabled = !isImporting && !isDownloading,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(stringResource(R.string.import_zip_from_device))
                }

                if (isImporting) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "Importing driver...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (installedDrivers.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "Installed custom drivers",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        for (id in installedDrivers) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                val meta = driverMeta[id]
                                val display = buildString {
                                    if (!meta?.first.isNullOrEmpty()) append(meta?.first) else append(id)
                                }
                                Text(
                                    text = display,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(
                                    onClick = { driverToDelete = id },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    // Confirmation dialog for deletion
                    driverToDelete?.let { id ->
                        AlertDialog(
                            onDismissRequest = { driverToDelete = null },
                            title = { Text(text = stringResource(R.string.confirm_delete)) },
                            text = { Text(text = stringResource(R.string.remove_driver_confirmation, id)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    try {
                                        AdrenotoolsManager(ctx).removeDriver(id)
                                        lastMessage = "Removed driver: $id"
                                        Toast.makeText(ctx, "Removed driver: $id", Toast.LENGTH_SHORT).show()
                                        refreshDriverList()
                                    } catch (e: Exception) {
                                        lastMessage = "Error removing $id: ${e.message}"
                                        Toast.makeText(ctx, "Error removing $id: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                    driverToDelete = null
                                }) {
                                    Text(
                                        text = "Delete",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { driverToDelete = null }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Close",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
    )
}

private fun handlePickedUri(context: Context, uri: Uri): String {
    return try {
        val name = AdrenotoolsManager(context).installDriver(uri)
        if (name.isNotEmpty()) {
            "Installed driver: $name"
        } else {
            "Failed to install driver: driver already installed or .zip corrupted"
        }
    } catch (e: Exception) {
        "Error importing driver: ${e.message}"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_DriverManagerDialog() {
    PluviaTheme {
        Surface {
            DriverManagerDialog(open = true, onDismiss = { })
        }
    }
}
