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
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamService
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WineProtonManagerDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var pendingProfile by remember { mutableStateOf<ContentProfile?>(null) }
    val untrustedFiles = remember { mutableStateListOf<ContentProfile.ContentFile>() }
    var showUntrustedConfirm by remember { mutableStateOf(false) }

    val mgr = remember(ctx) { ContentsManager(ctx) }

    // Installed list state
    val installedProfiles = remember { mutableStateListOf<ContentProfile>() }
    var deleteTarget by remember { mutableStateOf<ContentProfile?>(null) }

    val refreshInstalled: () -> Unit = {
        try {
            mgr.syncContents()
        } catch (_: Exception) {}
        installedProfiles.clear()
        android.util.Log.d("WineProtonManager", "Refreshing installed profiles, list cleared")
        try {
            // Get both Wine and Proton profiles
            val wineList = mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE)
            val protonList = mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)
            android.util.Log.d("WineProtonManager", "Wine profiles: ${wineList?.size ?: 0}, Proton profiles: ${protonList?.size ?: 0}")
            if (wineList != null) installedProfiles.addAll(wineList.filter { it.remoteUrl == null })
            if (protonList != null) installedProfiles.addAll(protonList.filter { it.remoteUrl == null })
            android.util.Log.d("WineProtonManager", "Total installed profiles after refresh: ${installedProfiles.size}")
        } catch (e: Exception) {
            android.util.Log.e("WineProtonManager", "Error refreshing profiles", e)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { mgr.syncContents() }
        refreshInstalled()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            SteamService.isImporting = false
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            isBusy = true
            statusMessage = "Validating Wine/Proton package..."

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
                statusMessage = "Filename must begin with 'wine' or 'proton' (case-insensitive)"
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
                            err = Exception("File is empty or cannot be read")
                            latch.countDown()
                            return@withContext Triple(profile, failReason, err)
                        }
                    } ?: run {
                        err = Exception("Cannot open file")
                        latch.countDown()
                        return@withContext Triple(profile, failReason, err)
                    }

                    mgr.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                        override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception) {
                            android.util.Log.e("WineProtonManager", "Extraction failed: $reason", e)
                            failReason = reason
                            err = e
                            latch.countDown()
                        }

                        override fun onSucceed(profileArg: ContentProfile) {
                            android.util.Log.d("WineProtonManager", "Extraction succeeded, profile: ${profileArg.verName}")
                            profile = profileArg
                            latch.countDown()
                        }
                    })
                } catch (e: Exception) {
                    err = e
                    latch.countDown()
                }
                latch.await()
                Triple(profile, failReason, err)
            }

            val (profile, fail, error) = result
            if (profile == null) {
                val msg = when (fail) {
                    ContentsManager.InstallFailedReason.ERROR_BADTAR -> "File cannot be recognized as valid archive"
                    ContentsManager.InstallFailedReason.ERROR_NOPROFILE -> "profile.json not found in package"
                    ContentsManager.InstallFailedReason.ERROR_BADPROFILE -> "profile.json is invalid"
                    ContentsManager.InstallFailedReason.ERROR_EXIST -> "This Wine/Proton version already exists"
                    ContentsManager.InstallFailedReason.ERROR_MISSINGFILES -> "Package is missing required files (bin/, lib/, or prefixPack.txz)"
                    ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> "Package cannot be trusted"
                    ContentsManager.InstallFailedReason.ERROR_NOSPACE -> "Not enough storage space"
                    null -> error?.let { "Error: ${it.javaClass.simpleName} - ${it.message}" } ?: "Unknown error occurred"
                    else -> "Unable to install Wine/Proton package"
                }
                statusMessage = if (error != null && fail != null) {
                    "$msg: ${error.message ?: error.javaClass.simpleName}"
                } else {
                    error?.message?.let { "$msg: $it" } ?: msg
                }
                android.util.Log.e("WineProtonManager", "Import failed: $statusMessage", error)
                Toast.makeText(ctx, statusMessage, Toast.LENGTH_LONG).show()
                isBusy = false
                SteamService.isImporting = false
                return@launch
            }

            // Validate it's Wine or Proton and matches detected type
            if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE &&
                profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                statusMessage = "Package is not Wine or Proton (type: ${profile.type})"
                Toast.makeText(ctx, statusMessage, Toast.LENGTH_LONG).show()
                isBusy = false
                SteamService.isImporting = false
                return@launch
            }

            // Verify detected type matches package type
            if (profile.type != detectedType) {
                statusMessage = "Filename indicates $detectedType but package contains ${profile.type}"
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
                statusMessage = "This package includes files outside the trusted set."
                isBusy = false
            } else {
                // Safe to finish install directly
                performFinishInstall(ctx, mgr, profile) { msg ->
                    pendingProfile = null
                    refreshInstalled()
                    statusMessage = msg
                    isBusy = false
                }
            }
            SteamService.isImporting = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Wine/Proton Manager", style = MaterialTheme.typography.titleLarge) },
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
                                text = "BIONIC ONLY",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Import custom Wine or Proton versions for Bionic containers. " +
                                       "Filename must begin with 'wine' or 'proton' (case-insensitive). " +
                                       "Packages must include bin/, lib/, and prefixPack.txz. " +
                                       "All imports are bionic-compatible only.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Text(
                    text = "Import Wine/Proton Package",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Select a .wcp file (tar.xz or tar.zst archive) with filename starting with 'wine' or 'proton'",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Button(
                    onClick = {
                        try {
                            SteamService.isImporting = true
                            // Only allow .wcp files
                            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        } catch (e: Exception) {
                            SteamService.isImporting = false
                            Toast.makeText(ctx, "Failed to open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) { Text("Import .wcp Package") }

                if (isBusy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text(text = statusMessage ?: "Processing...")
                    }
                } else if (!statusMessage.isNullOrEmpty()) {
                    Text(
                        text = statusMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                pendingProfile?.let { profile ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(text = "Package Details", style = MaterialTheme.typography.titleMedium)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        InfoRow(label = "Type", value = profile.type.toString())
                        InfoRow(label = "Version", value = profile.verName)
                        InfoRow(label = "Version Code", value = profile.verCode.toString())
                        profile.wineBinPath?.let { binPath ->
                            InfoRow(label = "Bin Path", value = binPath)
                        }
                        profile.wineLibPath?.let { libPath ->
                            InfoRow(label = "Lib Path", value = libPath)
                        }
                        if (!profile.desc.isNullOrEmpty()) {
                            InfoRow(label = "Description", value = profile.desc)
                        }
                    }

                    if (untrustedFiles.isEmpty()) {
                        Text(
                            text = "✓ All files are trusted. Ready to install.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    performFinishInstall(ctx, mgr, profile) { msg ->
                                        pendingProfile = null
                                        refreshInstalled()
                                        statusMessage = msg
                                    }
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("Install Package") }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(text = "Installed Wine/Proton Versions", style = MaterialTheme.typography.titleMedium)

                // Installed list
                if (installedProfiles.isEmpty()) {
                    Text(
                        text = "No installed Wine or Proton versions found.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        installedProfiles.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "${p.type}: ${p.verName} (${p.verCode})", style = MaterialTheme.typography.bodyMedium)
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
                                        contentDescription = "Delete",
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
            title = { Text("Untrusted Files Detected") },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "This package includes files outside the trusted set. Review and confirm to proceed with installation.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "Untrusted files:",
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
                        performFinishInstall(ctx, mgr, profile) { msg ->
                            pendingProfile = null
                            refreshInstalled()
                            statusMessage = msg
                            isBusy = false
                        }
                    }
                }) { Text("Install Anyway") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUntrustedConfirm = false
                    pendingProfile = null
                    statusMessage = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Delete confirmation
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove Wine/Proton Version") },
            text = {
                Text("Are you sure you want to remove ${target.type} ${target.verName} (${target.verCode})? " +
                     "Containers using this version will no longer work.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { 
                        try {
                            android.util.Log.d("WineProtonManager", "Attempting to delete: ${target.type} ${target.verName} (${target.verCode})")
                            withContext(Dispatchers.IO) {
                                mgr.removeContent(target)
                            }
                            android.util.Log.d("WineProtonManager", "Delete completed successfully")
                            // Refresh on main thread
                            withContext(Dispatchers.Main) {
                                refreshInstalled()
                                Toast.makeText(ctx, "Removed ${target.verName}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("WineProtonManager", "Delete failed", e)
                            Toast.makeText(ctx, "Failed to remove: ${e.message}", Toast.LENGTH_LONG).show()
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
    onDone: (String) -> Unit,
) {
    val msg = withContext(Dispatchers.IO) {
        var message = ""
        val latch = CountDownLatch(1)
        try {
            mgr.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception) {
                    message = when (reason) {
                        ContentsManager.InstallFailedReason.ERROR_EXIST -> "Wine/Proton version already exists"
                        ContentsManager.InstallFailedReason.ERROR_NOSPACE -> "Not enough storage space"
                        else -> "Failed to install: ${e.message ?: "Unknown error"}"
                    }
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    message = "${profileArg.type} ${profileArg.verName} installed successfully"
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            message = "Installation error: ${e.message}"
            latch.countDown()
        }
        latch.await()
        message
    }
    onDone(msg)
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
