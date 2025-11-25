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
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WineProtonManagerDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isStatusSuccess by remember { mutableStateOf(false) }

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
            // Use a set to track unique profiles by verName to avoid duplicates
            val seenVersions = mutableSetOf<String>()
            // Get both Wine and Proton profiles
            val wineList = mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE)
            val protonList = mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)
            android.util.Log.d("WineProtonManager", "Wine profiles from manager: ${wineList?.size ?: 0}, Proton profiles: ${protonList?.size ?: 0}")

            if (wineList != null) {
                val filtered = wineList.filter { it.remoteUrl == null && seenVersions.add(it.verName) }
                android.util.Log.d("WineProtonManager", "Adding ${filtered.size} Wine profiles:")
                filtered.forEach { android.util.Log.d("WineProtonManager", "  - ${it.type}: ${it.verName}") }
                installedProfiles.addAll(filtered)
            }
            if (protonList != null) {
                val filtered = protonList.filter { it.remoteUrl == null && seenVersions.add(it.verName) }
                android.util.Log.d("WineProtonManager", "Adding ${filtered.size} Proton profiles:")
                filtered.forEach { android.util.Log.d("WineProtonManager", "  - ${it.type}: ${it.verName}") }
                installedProfiles.addAll(filtered)
            }
            android.util.Log.d("WineProtonManager", "=== Total installed profiles after refresh: ${installedProfiles.size} ===")
        } catch (e: Exception) {
            android.util.Log.e("WineProtonManager", "Error refreshing profiles", e)
        }
    }

    LaunchedEffect(open) {
        if (open) {
            try {
                withContext(Dispatchers.IO) { mgr.syncContents() }
            } catch (_: Exception) {}
            refreshInstalled()
        }
    }

    // Cleanup on dialog dismiss
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // Always reset importing flag when dialog closes
            // If there's an actual import in progress, it will complete in the background
            android.util.Log.d("WineProtonManager", "Dialog closing, resetting isImporting flag (was busy: $isBusy)")
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

            android.util.Log.d("WineProtonManager", "Detected filename: $filename")

            val filenameLower = filename.lowercase()
            val detectedType = when {
                filenameLower.startsWith("wine") -> ContentProfile.ContentType.CONTENT_TYPE_WINE
                filenameLower.startsWith("proton") -> ContentProfile.ContentType.CONTENT_TYPE_PROTON
                else -> null
            }

            android.util.Log.d("WineProtonManager", "Detected type: $detectedType")

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

                    android.util.Log.d("WineProtonManager", "Starting extraction and validation...")
                    val startTime = System.currentTimeMillis()

                    mgr.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                        override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                            android.util.Log.e("WineProtonManager", "Extraction failed after ${elapsed}s: $reason", e)
                            failReason = reason
                            err = e
                            latch.countDown()
                        }

                        override fun onSucceed(profileArg: ContentProfile) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                            android.util.Log.d("WineProtonManager", "Extraction succeeded after ${elapsed}s, profile: ${profileArg.verName}")
                            profile = profileArg
                            latch.countDown()
                        }
                    })
                } catch (e: Exception) {
                    android.util.Log.e("WineProtonManager", "Exception during extraction", e)
                    err = e
                    latch.countDown()
                }
                android.util.Log.d("WineProtonManager", "Waiting for extraction to complete...")
                latch.await()
                android.util.Log.d("WineProtonManager", "Extraction wait completed")
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
                android.util.Log.e("WineProtonManager", "Import failed: $statusMessage", error)
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
            val installDir = ContentsManager.getInstallDir(ctx, profile)
            val binaryVariant = detectBinaryVariant(installDir)
            android.util.Log.d("WineProtonManager", "Detected binary variant: $binaryVariant")

            if (binaryVariant == "glibc") {
                // Reject glibc builds - not supported in GameNative
                statusMessage = ctx.getString(R.string.wine_proton_glibc_incompatible)
                isStatusSuccess = false

                // Clean up the extracted files
                try {
                    withContext(Dispatchers.IO) {
                        mgr.removeContent(profile)
                        android.util.Log.d("WineProtonManager", "Removed incompatible glibc build: ${profile.verName}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WineProtonManager", "Error removing glibc build", e)
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
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
                    android.util.Log.d("WineProtonManager", "Delete button clicked for: ${target.type} ${target.verName}")
                    scope.launch {
                        try {
                            android.util.Log.d("WineProtonManager", "Attempting to delete: ${target.type} ${target.verName} (${target.verCode})")
                            withContext(Dispatchers.IO) {
                                android.util.Log.d("WineProtonManager", "Calling mgr.removeContent()...")
                                mgr.removeContent(target)
                                android.util.Log.d("WineProtonManager", "removeContent() completed, calling syncContents()...")
                                mgr.syncContents()
                                android.util.Log.d("WineProtonManager", "syncContents() completed")
                            }
                            android.util.Log.d("WineProtonManager", "Delete completed successfully, now refreshing UI")
                            // Refresh on main thread
                            withContext(Dispatchers.Main) {
                                android.util.Log.d("WineProtonManager", "About to call refreshInstalled() after deletion")
                                refreshInstalled()
                                android.util.Log.d("WineProtonManager", "refreshInstalled() completed after deletion")
                                Toast.makeText(ctx, ctx.getString(R.string.wine_proton_removed_toast, target.verName), Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("WineProtonManager", "Delete failed", e)
                            Toast.makeText(ctx, ctx.getString(R.string.wine_proton_remove_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                        android.util.Log.d("WineProtonManager", "Setting deleteTarget to null")
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
    val result = withContext(Dispatchers.IO) {
        var message = ""
        var success = false
        val latch = CountDownLatch(1)
        try {
            mgr.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception) {
                    message = when (reason) {
                        ContentsManager.InstallFailedReason.ERROR_EXIST -> context.getString(R.string.wine_proton_version_already_exists)
                        ContentsManager.InstallFailedReason.ERROR_NOSPACE -> context.getString(R.string.wine_proton_error_nospace)
                        else -> context.getString(R.string.wine_proton_install_failed, e.message ?: context.getString(R.string.wine_proton_error_unknown))
                    }
                    success = false
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    message = context.getString(R.string.wine_proton_install_success, profileArg.type, profileArg.verName)
                    success = true
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            message = context.getString(R.string.wine_proton_install_error, e.message ?: "")
            success = false
            latch.countDown()
        }
        latch.await()

        // Sync contents after installation completes (success or failure)
        try {
            mgr.syncContents()
        } catch (e: Exception) {
            android.util.Log.e("WineProtonManager", "Error syncing contents after install", e)
        }

        message to success
    }
    onDone(result.first, result.second)
    Toast.makeText(context, result.first, Toast.LENGTH_SHORT).show()
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
                android.util.Log.w("WineProtonManager", "No wine binary found in ${installDir.path}")
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
        android.util.Log.e("WineProtonManager", "Error detecting binary variant", e)
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
