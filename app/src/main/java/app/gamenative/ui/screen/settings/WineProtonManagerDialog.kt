package app.gamenative.ui.screen.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.utils.Net
import com.winlator.core.StringUtils
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WineProtonManagerDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isStatusSuccess by remember { mutableStateOf(false) }

    // Online download state
    var isDownloading by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }

    // Wine/Proton manifest handling
    var wineProtonManifest by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoadingManifest by remember { mutableStateOf(true) }
    var manifestError by remember { mutableStateOf<String?>(null) }

    // Dropdown state
    var isExpanded by remember { mutableStateOf(false) }
    var selectedWineKey by remember { mutableStateOf("") }

    var pendingProfile by remember { mutableStateOf<ContentProfile?>(null) }
    val untrustedFiles = remember { mutableStateListOf<ContentProfile.ContentFile>() }
    var showUntrustedConfirm by remember { mutableStateOf(false) }

    val mgr = remember(ctx) { ContentsManager(ctx) }

    // Installed list state
    val installedProfiles = remember { mutableStateListOf<ContentProfile>() }
    var deleteTarget by remember { mutableStateOf<ContentProfile?>(null) }

    val refreshInstalled: () -> Unit = {
        installedProfiles.clear()
        try {
            // Use a set to track unique profiles by type+verName to avoid duplicates
            val seenProfiles = mutableSetOf<Pair<ContentProfile.ContentType, String>>()
            // Get both Wine and Proton profiles
            val wineList = mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE)
            val protonList = mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)
            Timber.tag("WineProtonManagerDialog").d("Wine profiles from manager: ${wineList?.size ?: 0}, Proton profiles: ${protonList?.size ?: 0}")

            if (wineList != null) {
                val filtered = wineList.filter { it.remoteUrl == null && seenProfiles.add(Pair(it.type, it.verName)) }
                installedProfiles.addAll(filtered)
            }
            if (protonList != null) {
                val filtered = protonList.filter { it.remoteUrl == null && seenProfiles.add(Pair(it.type, it.verName)) }
                installedProfiles.addAll(filtered)
            }
            Timber.tag("WineProtonManagerDialog").d("=== Total installed profiles after refresh: ${installedProfiles.size} ===")
        } catch (e: Exception) {
            Timber.tag("WineProtonManagerDialog").e(e, "Error refreshing profiles")
        }
    }

    // Grab Wine Versions based on the manifest and populate the Options
    LaunchedEffect(open) {
        if (open) {
            manifestError = null
            isLoadingManifest = true
            try {
                withContext(Dispatchers.IO) { mgr.syncContents() }
            } catch (_: Exception) {}
            refreshInstalled()
            scope.launch(Dispatchers.IO) {
                loadWineProtonManifest(
                    onSuccess = { manifest ->
                        wineProtonManifest = manifest
                        isLoadingManifest = false
                        manifestError = null
                    },
                    onError = { error ->
                        manifestError = error
                        isLoadingManifest = false
                    }
                )
            }
        }
    }

    // Cleanup on dialog dismiss
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // Always reset importing flag when dialog closes
            SteamService.isImporting = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            SteamService.isImporting = false
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            isBusy = true
            statusMessage = ctx.getString(R.string.wine_proton_extracting)

            // Get filename and detect type
            val filename = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment ?: "unknown"

            val filenameLower = filename.lowercase()
            val detectedType = when {
                filenameLower.contains("wine") -> ContentProfile.ContentType.CONTENT_TYPE_WINE
                filenameLower.contains("proton") -> ContentProfile.ContentType.CONTENT_TYPE_PROTON
                else -> null
            }


            if (detectedType == null) {
                statusMessage = ctx.getString(R.string.wine_proton_filename_error)
                isStatusSuccess = false
                Toast.makeText(ctx, statusMessage, Toast.LENGTH_LONG).show()
                isBusy = false
                SteamService.isImporting = false
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                var profile: ContentProfile? = null
                var failReason: ContentsManager.InstallFailedReason? = null
                var err: Exception? = null
                val latch = CountDownLatch(1)
                try {
                    // Validate file exists and is readable
                    ctx.contentResolver.openInputStream(uri)?.use { stream ->
                        if (stream.available() == 0) {
                            err = Exception(ctx.getString(R.string.wine_proton_file_empty))
                            latch.countDown()
                            return@withContext Triple(profile, failReason, err)
                        }
                    } ?: run {
                        err = Exception(ctx.getString(R.string.wine_proton_cannot_open))
                        latch.countDown()
                        return@withContext Triple(profile, failReason, err)
                    }

                    val startTime = System.currentTimeMillis()

                    mgr.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                        override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception) {
                            failReason = reason
                            err = e
                            latch.countDown()
                        }

                        override fun onSucceed(profileArg: ContentProfile) {
                            profile = profileArg
                            latch.countDown()
                        }
                    })
                } catch (e: Exception) {
                    Timber.tag("WineProtonManagerDialog").e(e, "Exception during extraction")
                    err = e
                    latch.countDown()
                }
                // 4 minutes worth of extration time should be plenty of time.
                if (!latch.await(240, TimeUnit.SECONDS)) {
                    err = Exception("Extraction timed out after 240 seconds")
                }
                Triple(profile, failReason, err)
            }

            val (profile, fail, error) = result
            if (profile == null) {
                val msg = when (fail) {
                    ContentsManager.InstallFailedReason.ERROR_BADTAR -> ctx.getString(R.string.wine_proton_error_badtar)
                    ContentsManager.InstallFailedReason.ERROR_NOPROFILE -> ctx.getString(R.string.wine_proton_error_noprofile)
                    ContentsManager.InstallFailedReason.ERROR_BADPROFILE -> ctx.getString(R.string.wine_proton_error_badprofile)
                    ContentsManager.InstallFailedReason.ERROR_EXIST -> ctx.getString(R.string.wine_proton_error_exist)
                    ContentsManager.InstallFailedReason.ERROR_MISSINGFILES -> ctx.getString(R.string.wine_proton_error_missingfiles)
                    ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> ctx.getString(R.string.wine_proton_error_untrustprofile)
                    ContentsManager.InstallFailedReason.ERROR_NOSPACE -> ctx.getString(R.string.wine_proton_error_nospace)
                    null -> error?.let { "Error: ${it.javaClass.simpleName} - ${it.message}" } ?: ctx.getString(R.string.wine_proton_error_unknown)
                    else -> ctx.getString(R.string.wine_proton_error_unable_install)
                }
                statusMessage = if (error != null && fail != null) {
                    "$msg: ${error.message ?: error.javaClass.simpleName}"
                } else {
                    error?.message?.let { "$msg: $it" } ?: msg
                }
                isStatusSuccess = false
                Timber.tag("WineProtonManagerDialog").e(error, "Import failed: $statusMessage")
                Toast.makeText(ctx, statusMessage, Toast.LENGTH_LONG).show()
                isBusy = false
                SteamService.isImporting = false
                return@launch
            }

            // Validate it's Wine or Proton and matches detected type
            if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE &&
                profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                statusMessage = ctx.getString(R.string.wine_proton_not_wine_or_proton, profile.type)
                isStatusSuccess = false
                Toast.makeText(ctx, statusMessage, Toast.LENGTH_LONG).show()
                isBusy = false
                SteamService.isImporting = false
                return@launch
            }

            // Verify detected type matches package type
            if (profile.type != detectedType) {
                statusMessage = ctx.getString(R.string.wine_proton_type_mismatch, detectedType, profile.type)
                isStatusSuccess = false
                Toast.makeText(ctx, statusMessage, Toast.LENGTH_LONG).show()
                isBusy = false
                SteamService.isImporting = false
                return@launch
            }

            // Detect binary variant (glibc vs bionic)
            // Note: Files are still in tmp directory at this point, not yet moved to install location
            val tmpDir = ContentsManager.getTmpDir(ctx)
            val binaryVariant = detectBinaryVariant(tmpDir)

            if (binaryVariant == "glibc") {
                // Reject glibc builds - not supported in GameNative
                statusMessage = ctx.getString(R.string.wine_proton_glibc_incompatible)
                isStatusSuccess = false

                // Clean up the extracted files from tmp directory
                try {
                    withContext(Dispatchers.IO) {
                        ContentsManager.cleanTmpDir(ctx)
                    }
                } catch (e: Exception) {
                    Timber.tag("WineProtonManagerDialog").e(e, "Error cleaning tmp dir")
                }

                Toast.makeText(ctx, statusMessage, Toast.LENGTH_LONG).show()
                isBusy = false
                SteamService.isImporting = false
                return@launch
            }

            pendingProfile = profile
            // Compute untrusted files and show confirmation if any
            val files = withContext(Dispatchers.IO) { mgr.getUnTrustedContentFiles(profile) }
            untrustedFiles.clear()
            untrustedFiles.addAll(files)
            if (untrustedFiles.isNotEmpty()) {
                showUntrustedConfirm = true
                statusMessage = ctx.getString(R.string.wine_proton_untrusted_files_detected)
                isStatusSuccess = false
                isBusy = false
            } else {
                // Safe to finish install directly
                performFinishInstall(ctx, mgr, profile) { msg, success ->
                    pendingProfile = null
                    refreshInstalled()
                    statusMessage = msg
                    isStatusSuccess = success
                    isBusy = false
                }
            }
            SteamService.isImporting = false
        }
    }

    // Function to download and install Wine/Proton from URL
    val downloadAndInstallWineProton = { wineFileName: String ->
        scope.launch {
            val overallStart = System.currentTimeMillis()
            isDownloading = true
            downloadProgress = 0f
            try {
                val destFile = File(ctx.cacheDir, wineFileName)
                var lastUpdate = 0L

                // Use shared downloader with automatic domain fallback
                SteamService.fetchFileWithFallback(
                    fileName = "$wineFileName",
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

                withContext(Dispatchers.Main) {
                    isDownloading = false
                    downloadProgress = 1f
                }

                // Install the Wine/Proton from the temporary file
                withContext(Dispatchers.Main) {
                    isInstalling = true
                    isBusy = true
                    statusMessage = ctx.getString(R.string.wine_proton_extracting)
                }

                Timber.d("WineProtonManagerDialog: Starting install")
                val uri = Uri.fromFile(destFile)
                val installStart = System.currentTimeMillis()

                // Get filename and detect type
                val filenameLower = wineFileName.lowercase()
                val detectedType = when {
                    filenameLower.startsWith("wine") -> ContentProfile.ContentType.CONTENT_TYPE_WINE
                    filenameLower.startsWith("proton") -> ContentProfile.ContentType.CONTENT_TYPE_PROTON
                    else -> null
                }

                if (detectedType == null) {
                    val errorMsg = ctx.getString(R.string.wine_proton_filename_error)
                    withContext(Dispatchers.Main) {
                        statusMessage = errorMsg
                        isStatusSuccess = false
                        Toast.makeText(ctx, errorMsg, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    var profile: ContentProfile? = null
                    var failReason: ContentsManager.InstallFailedReason? = null
                    var err: Exception? = null
                    val latch = CountDownLatch(1)
                    try {
                        mgr.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception) {
                                failReason = reason
                                err = e
                                latch.countDown()
                            }

                            override fun onSucceed(profileArg: ContentProfile) {
                                profile = profileArg
                                latch.countDown()
                            }
                        })
                    } catch (e: Exception) {
                        err = e
                        latch.countDown()
                    }
                if (!latch.await(240, TimeUnit.SECONDS)) {
                       err = Exception("Installation timed out after 240 seconds")
                   }
                    Triple(profile, failReason, err)
                }

                val (profile, fail, error) = result
                if (profile == null) {
                    val msg = when (fail) {
                        ContentsManager.InstallFailedReason.ERROR_BADTAR -> ctx.getString(R.string.wine_proton_error_badtar)
                        ContentsManager.InstallFailedReason.ERROR_NOPROFILE -> ctx.getString(R.string.wine_proton_error_noprofile)
                        ContentsManager.InstallFailedReason.ERROR_BADPROFILE -> ctx.getString(R.string.wine_proton_error_badprofile)
                        ContentsManager.InstallFailedReason.ERROR_EXIST -> ctx.getString(R.string.wine_proton_error_exist)
                        ContentsManager.InstallFailedReason.ERROR_MISSINGFILES -> ctx.getString(R.string.wine_proton_error_missingfiles)
                        ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> ctx.getString(R.string.wine_proton_error_untrustprofile)
                        ContentsManager.InstallFailedReason.ERROR_NOSPACE -> ctx.getString(R.string.wine_proton_error_nospace)
                        null -> error?.let { "Error: ${it.javaClass.simpleName} - ${it.message}" } ?: ctx.getString(R.string.wine_proton_error_unknown)
                        else -> ctx.getString(R.string.wine_proton_error_unable_install)
                    }
                    val errorMessage = if (error != null && fail != null) {
                        "$msg: ${error.message ?: error.javaClass.simpleName}"
                    } else {
                        error?.message?.let { "$msg: $it" } ?: msg
                    }
                    withContext(Dispatchers.Main) {
                        statusMessage = errorMessage
                        isStatusSuccess = false
                        Toast.makeText(ctx, errorMessage, Toast.LENGTH_LONG).show()
                    }
                    Timber.e(error, "WineProtonManagerDialog: Install failed")
                    return@launch
                }

                // Validate it's Wine or Proton and matches detected type
                if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE &&
                    profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                    val errorMsg = ctx.getString(R.string.wine_proton_not_wine_or_proton, profile.type)
                    withContext(Dispatchers.Main) {
                        statusMessage = errorMsg
                        isStatusSuccess = false
                        Toast.makeText(ctx, errorMsg, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                if (profile.type != detectedType) {
                    val errorMsg = ctx.getString(R.string.wine_proton_type_mismatch, detectedType, profile.type)
                    withContext(Dispatchers.Main) {
                        statusMessage = errorMsg
                        isStatusSuccess = false
                        Toast.makeText(ctx, errorMsg, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Detect binary variant
                // Note: Files are still in tmp directory at this point, not yet moved to install location
                val tmpDir = ContentsManager.getTmpDir(ctx)
                val binaryVariant = detectBinaryVariant(tmpDir)

                //! We currently are not supporting GLIBC but we will in future.
                if (binaryVariant == "glibc") {
                    val errorMsg = ctx.getString(R.string.wine_proton_glibc_incompatible)
                    withContext(Dispatchers.Main) {
                        statusMessage = errorMsg
                        isStatusSuccess = false
                        Toast.makeText(ctx, errorMsg, Toast.LENGTH_LONG).show()
                    }
                    try {
                        ContentsManager.cleanTmpDir(ctx)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to clean tmp dir")
                    }
                    return@launch
                }

                // Check for untrusted files
                val files = withContext(Dispatchers.IO) { mgr.getUnTrustedContentFiles(profile) }
                untrustedFiles.clear()
                untrustedFiles.addAll(files)

                if (untrustedFiles.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        pendingProfile = profile
                        showUntrustedConfirm = true
                        statusMessage = ctx.getString(R.string.wine_proton_untrusted_files_detected)
                        isStatusSuccess = false
                    }
                } else {
                    // Safe to finish install directly
                    performFinishInstall(ctx, mgr, profile) { msg, success ->
                        scope.launch(Dispatchers.Main) {
                            pendingProfile = null
                            refreshInstalled()
                            statusMessage = msg
                            isStatusSuccess = success
                        }
                    }
                }

                val installDurationMs = System.currentTimeMillis() - installStart
                Timber.d("WineProtonManagerDialog: Download+Install total ${(System.currentTimeMillis() - overallStart)}ms")

                // Delete the temporary file
                withContext(Dispatchers.IO) {
                    destFile.delete()
                }
            } catch (e: SocketTimeoutException) {
                val errorMessage = "Connection timed out. Please check your network and try again."
                withContext(Dispatchers.Main) {
                    statusMessage = errorMessage
                    isStatusSuccess = false
                    Toast.makeText(ctx, errorMessage, Toast.LENGTH_SHORT).show()
                }
                Timber.e(e, "WineProtonManagerDialog: Download timeout")
            } catch (e: IOException) {
                val errorMessage = if (e.message?.contains("timeout", ignoreCase = true) == true) {
                    "Connection timed out. Please check your network and try again."
                } else {
                    "Network error: ${e.message}"
                }
                withContext(Dispatchers.Main) {
                    statusMessage = errorMessage
                    isStatusSuccess = false
                    Toast.makeText(ctx, errorMessage, Toast.LENGTH_SHORT).show()
                }
                Timber.e(e, "WineProtonManagerDialog: Download failed with IO error")
            } catch (e: Exception) {
                val errorMessage = "Error downloading/installing: ${e.message}"
                withContext(Dispatchers.Main) {
                    statusMessage = errorMessage
                    isStatusSuccess = false
                    Toast.makeText(ctx, errorMessage, Toast.LENGTH_SHORT).show()
                }
                Timber.e(e, "WineProtonManagerDialog: Download/install failed")
            } finally {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    isInstalling = false
                    isBusy = false
                    downloadProgress = 0f
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.wine_proton_manager), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Info card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Info",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.wine_proton_bionic_notice_header),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.wine_proton_info_description),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                // Online Wine/Proton selection
                if (isLoadingManifest) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Loading available Wine/Proton versions...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.padding(start = 8.dp).height(24.dp)
                        )
                    }
                } else if (manifestError != null) {
                    Text(
                        text = manifestError ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else if (wineProtonManifest.isNotEmpty()) {
                    Text(
                        text = "Available online versions:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = isExpanded,
                        onExpandedChange = { isExpanded = !isExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedWineKey,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Wine/Proton version") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )

                        ExposedDropdownMenu(
                            expanded = isExpanded,
                            onDismissRequest = { isExpanded = false }
                        ) {
                            wineProtonManifest.keys.sorted().forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key) },
                                    onClick = {
                                        selectedWineKey = key
                                        isExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedWineKey.isNotEmpty() && wineProtonManifest.containsKey(selectedWineKey)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Button(
                                onClick = { downloadAndInstallWineProton(wineProtonManifest[selectedWineKey]!!) },
                                enabled = !isBusy && !isDownloading && !isInstalling,
                                modifier = Modifier.weight(1f)
                            ) {
                                when {
                                    isDownloading -> Text("Downloading...")
                                    isInstalling -> Text("Installing...")
                                    else -> Text("Download & Install")
                                }
                            }
                        }

                        if (isDownloading) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Downloading: ${(downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        if (isInstalling) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(
                                    text = "Installing Wine/Proton package...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(start = 8.dp).height(24.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Local import section
                Text(
                    text = "Import from local storage:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.wine_proton_import_package),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.wine_proton_select_file_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = stringResource(R.string.win_proton_example),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        try {
                            SteamService.isImporting = true
                            // Only allow .wcp files
                            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        } catch (e: Exception) {
                            SteamService.isImporting = false
                            Toast.makeText(ctx, ctx.getString(R.string.wine_proton_failed_file_picker, e.message ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) { Text(stringResource(R.string.wine_proton_import_wcp_button)) }

                if (isBusy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text(text = statusMessage ?: stringResource(R.string.wine_proton_processing))
                    }
                } else if (!statusMessage.isNullOrEmpty()) {
                    Text(
                        text = statusMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isStatusSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                pendingProfile?.let { profile ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(text = stringResource(R.string.wine_proton_package_details), style = MaterialTheme.typography.titleMedium)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        InfoRow(label = stringResource(R.string.wine_proton_type), value = profile.type.toString())
                        InfoRow(label = stringResource(R.string.wine_proton_version), value = profile.verName)
                        InfoRow(label = stringResource(R.string.wine_proton_version_code), value = profile.verCode.toString())
                        profile.wineBinPath?.let { binPath ->
                            InfoRow(label = stringResource(R.string.wine_proton_bin_path), value = binPath)
                        }
                        profile.wineLibPath?.let { libPath ->
                            InfoRow(label = stringResource(R.string.wine_proton_lib_path), value = libPath)
                        }
                        if (!profile.desc.isNullOrEmpty()) {
                            InfoRow(label = stringResource(R.string.wine_proton_description), value = profile.desc)
                        }
                    }

                    if (untrustedFiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.wine_proton_all_files_trusted),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    performFinishInstall(ctx, mgr, profile) { msg, success ->
                                        pendingProfile = null
                                        refreshInstalled()
                                        statusMessage = msg
                                        isStatusSuccess = success
                                    }
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text(stringResource(R.string.wine_proton_install_package)) }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(text = stringResource(R.string.wine_proton_installed_versions), style = MaterialTheme.typography.titleMedium)

                if (installedProfiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.wine_proton_no_versions_found),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        installedProfiles.forEachIndexed { index, p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "${p.type}: ${p.verName}", style = MaterialTheme.typography.bodyMedium)
                                    if (!p.desc.isNullOrEmpty()) {
                                        Text(text = p.desc, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                IconButton(
                                    onClick = { deleteTarget = p },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.wine_proton_delete_content_desc),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            if (p != installedProfiles.last()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )

    // Untrusted files confirmation
    if (showUntrustedConfirm && pendingProfile != null) {
        AlertDialog(
            onDismissRequest = { showUntrustedConfirm = false },
            title = { Text(stringResource(R.string.untrusted_files_detected)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.wine_proton_untrusted_files_message),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = stringResource(R.string.wine_proton_untrusted_files_label),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    untrustedFiles.forEach { cf ->
                        Text(text = "• ${cf.target}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val profile = pendingProfile ?: return@TextButton
                    showUntrustedConfirm = false
                    isBusy = true
                    scope.launch {
                        performFinishInstall(ctx, mgr, pendingProfile!!) { msg, success ->
                            pendingProfile = null
                            refreshInstalled()
                            statusMessage = msg
                            isStatusSuccess = success
                            isBusy = false
                        }
                    }
                }) { Text(stringResource(R.string.install_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUntrustedConfirm = false
                    pendingProfile = null
                    statusMessage = null
                    isStatusSuccess = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Delete confirmation
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.wine_proton_remove_title)) },
            text = {
                Text(
                    text = stringResource(R.string.wine_proton_remove_message, target.type, target.verName, target.verCode)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                mgr.removeContent(target)
                                mgr.syncContents()
                            }
                            // Refresh on main thread
                            withContext(Dispatchers.Main) {
                                refreshInstalled()
                                Toast.makeText(ctx, ctx.getString(R.string.wine_proton_removed_toast, target.verName), Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Timber.tag("WineProtonManagerDialog").e(e, "Delete failed")
                            Toast.makeText(ctx, ctx.getString(R.string.wine_proton_remove_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                        deleteTarget = null
                    }
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private suspend fun performFinishInstall(
    context: Context,
    mgr: ContentsManager,
    profile: ContentProfile,
    onDone: (String, Boolean) -> Unit,
) {
    Timber.tag("WineProtonManagerDialog").d("📦 performFinishInstall called for: type=${profile.type}, verName=${profile.verName}, verCode=${profile.verCode}")
    val result = withContext(Dispatchers.IO) {
        var message = ""
        var success = false
        val latch = CountDownLatch(1)
        try {
            mgr.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception) {
                    Timber.tag("WineProtonManagerDialog").e(e, "   ❌ finishInstallContent FAILED: $reason")
                    message = when (reason) {
                        ContentsManager.InstallFailedReason.ERROR_EXIST -> context.getString(R.string.wine_proton_version_already_exists)
                        ContentsManager.InstallFailedReason.ERROR_NOSPACE -> context.getString(R.string.wine_proton_error_nospace)
                        else -> context.getString(R.string.wine_proton_install_failed, e.message ?: context.getString(R.string.wine_proton_error_unknown))
                    }
                    success = false
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    Timber.tag("WineProtonManagerDialog").d("   ✅ finishInstallContent SUCCESS: type=${profileArg.type}, verName=${profileArg.verName}, verCode=${profileArg.verCode}")
                    message = context.getString(R.string.wine_proton_install_success, profileArg.type, profileArg.verName)
                    success = true
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            Timber.tag("WineProtonManagerDialog").e(e, "   ❌ Exception during finishInstallContent")
            message = context.getString(R.string.wine_proton_install_error, e.message ?: "")
            success = false
            latch.countDown()
        }
       if (!latch.await(240, TimeUnit.SECONDS)) {
           message = "Installation timed out after 240 seconds"
           success = false
       }

        // Sync contents after installation completes (success or failure)
        try {
            mgr.syncContents()
        } catch (e: Exception) {
            Timber.tag("WineProtonManagerDialog").e(e, "Error syncing contents after install")
        }

        message to success
    }
    Timber.tag("WineProtonManagerDialog").d("📦 performFinishInstall complete: success=${result.second}, message='${result.first}'")
    onDone(result.first, result.second)
    Toast.makeText(context, result.first, Toast.LENGTH_SHORT).show()
}

/**
 * Loads the Wine/Proton manifest from the remote server.
 * Filters entries to only include Wine and Proton packages.
 */
private suspend fun loadWineProtonManifest(
    onSuccess: suspend (Map<String, String>) -> Unit,
    onError: suspend (String) -> Unit
) {
    try {
        val manifestUrl = "https://downloads.gamenative.app/component-manifest.json"
        val request = Request.Builder()
            .url(manifestUrl)
            .build()

        val response = Net.http.newCall(request).execute()
        if (response.isSuccessful) {
            val jsonString = response.body?.string() ?: "{}"
            val jsonObject = Json.decodeFromString<JsonObject>(jsonString)

            val manifest = jsonObject.entries
                .filter { it.key.startsWith("wine", ignoreCase = true) ||
                         it.key.startsWith("proton", ignoreCase = true) }
                .associate { it.key to it.value.toString().removeSurrounding("\"") }

            withContext(Dispatchers.Main) {
                onSuccess(manifest)
            }
        } else {
            withContext(Dispatchers.Main) {
                onError("Failed to load manifest: ${response.code}")
            }
            Timber.w("WineProtonManagerDialog: Failed to load manifest HTTP=${response.code}")
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onError("Error loading manifest: ${e.message}")
        }
        Timber.e(e, "WineProtonManagerDialog: Error loading manifest")
    }
}

/**
 * Detects whether Wine/Proton binaries are built for glibc or bionic variant
 * by checking the dynamic linker interpreter in the ELF binary.
 */
private fun detectBinaryVariant(installDir: File): String {
    try {
        // Check wine64 binary first, fall back to wine
        val wine64 = File(installDir, "bin/wine64")
        val wine = File(installDir, "bin/wine")
        val binaryFile = when {
            wine64.exists() -> wine64
            wine.exists() -> wine
            else -> {
                Timber.w("No wine binary found in ${installDir.path}")
                return "unknown"
            }
        }

        // Read first 1KB of ELF file to find interpreter
        val bytes = binaryFile.inputStream().use { stream ->
            val buffer = ByteArray(1024)
            val read = stream.read(buffer)
            buffer.copyOf(read)
        }

        // Convert to string to search for interpreter path
        val content = String(bytes, Charsets.ISO_8859_1)

        return when {
            content.contains("/system/bin/linker") -> "bionic"
            content.contains("/lib64/ld-linux") || content.contains("/lib/ld-linux") -> "glibc"
            else -> "unknown"
        }
    } catch (e: Exception) {
        Timber.e("Error detecting binary variant: " + e)
        return "unknown"
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun WineProtonManagerDialogPreview() {
    MaterialTheme {
        WineProtonManagerDialog(open = true, onDismiss = {})
    }
}
