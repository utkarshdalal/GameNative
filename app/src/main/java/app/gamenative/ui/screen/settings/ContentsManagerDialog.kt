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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
fun ContentsManagerDialog(open: Boolean, onDismiss: () -> Unit) {
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
    var currentType by remember { mutableStateOf(ContentProfile.ContentType.CONTENT_TYPE_DXVK) }
    val installedProfiles = remember { mutableStateListOf<ContentProfile>() }
    var typeExpanded by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ContentProfile?>(null) }

    val refreshInstalled: () -> Unit = {
        try {
            mgr.syncContents()
        } catch (_: Exception) {}
        installedProfiles.clear()
        try {
            val list = mgr.getProfiles(currentType)
            if (list != null) installedProfiles.addAll(list.filter { it.remoteUrl == null })
        } catch (_: Exception) {}
    }

    LaunchedEffect(currentType) {
        withContext(Dispatchers.IO) { mgr.syncContents() }
        refreshInstalled()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            statusMessage = "Validating content..."
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
                latch.await()
                Triple(profile, failReason, err)
            }

            val (profile, fail, error) = result
            if (profile == null) {
                val msg = when (fail) {
                    ContentsManager.InstallFailedReason.ERROR_BADTAR -> "File cannot be recognized"
                    ContentsManager.InstallFailedReason.ERROR_NOPROFILE -> "Profile not found in content"
                    ContentsManager.InstallFailedReason.ERROR_BADPROFILE -> "Profile cannot be recognized"
                    ContentsManager.InstallFailedReason.ERROR_EXIST -> "Content already exists"
                    ContentsManager.InstallFailedReason.ERROR_MISSINGFILES -> "Content is incomplete"
                    ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> "Content cannot be trusted"
                    ContentsManager.InstallFailedReason.ERROR_NOSPACE -> "Not enough space"
                    else -> "Unable to install content"
                }
                statusMessage = error?.message?.let { "$msg: $it" } ?: msg
                Toast.makeText(ctx, statusMessage, Toast.LENGTH_SHORT).show()
                isBusy = false
                return@launch
            }

            pendingProfile = profile
            // Compute untrusted files and show confirmation if any
            val files = withContext(Dispatchers.IO) { mgr.getUnTrustedContentFiles(profile) }
            untrustedFiles.clear()
            untrustedFiles.addAll(files)
            if (untrustedFiles.isNotEmpty()) {
                showUntrustedConfirm = true
                statusMessage = "This content includes files outside the trusted set."
                isBusy = false
            } else {
                // Safe to finish install directly
                performFinishInstall(ctx, mgr, profile) { _ ->
                    // Hide details and refresh installed list
                    pendingProfile = null
                    currentType = profile.type
                    refreshInstalled()
                    statusMessage = null
                    isBusy = false
                }
            }
            SteamService.isImporting = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.contents_manager), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
            ) {
                Text(
                    text = "Install additional components (.wcp: tar.xz/zst)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Button(
                    onClick = {
                        // Let users pick any file; manager validates supported archives
                        SteamService.isImporting = true
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    enabled = !isBusy,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                ) { Text(stringResource(R.string.import_wcp_from_device)) }

                if (isBusy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text(text = statusMessage ?: stringResource(R.string.working))
                    }
                } else if (!statusMessage.isNullOrEmpty()) {
                    Text(text = statusMessage ?: "", style = MaterialTheme.typography.bodySmall)
                }

                pendingProfile?.let { profile ->
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(text = stringResource(R.string.selected_content), style = MaterialTheme.typography.titleMedium)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp)
                    ) {
                        InfoRow(label = "Type", value = profile.type.toString())
                        InfoRow(label = "Version", value = profile.verName)
                        InfoRow(label = "Code", value = profile.verCode.toString())
                        if (!profile.desc.isNullOrEmpty()) InfoRow(label = "Description", value = profile.desc)
                    }

                    if (untrustedFiles.isEmpty()) {
                        Text(
                            text = "All files are trusted. Ready to install.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    performFinishInstall(ctx, mgr, profile) { _ ->
                                        pendingProfile = null
                                        currentType = profile.type
                                        refreshInstalled()
                                        statusMessage = null
                                    }
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text(stringResource(R.string.install)) }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))
                Text(text = stringResource(R.string.installed_contents), style = MaterialTheme.typography.titleMedium)

                // Content type selector
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        value = currentType.toString(),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        placeholder = { Text(stringResource(R.string.select_type)) }
                    )

                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        val allowed = listOf(
                            ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                            ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                            ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
                            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE
                        )
                        ContentProfile.ContentType.values().filter { it in allowed }.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.toString()) },
                                onClick = {
                                    currentType = t
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                // Installed list
                if (installedProfiles.isEmpty()) {
                    Text(
                        text = "No installed content for this type.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp)
                    ) {
                        installedProfiles.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "${p.verName} (${p.verCode})", style = MaterialTheme.typography.bodyMedium)
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

    if (showUntrustedConfirm && pendingProfile != null) {
        AlertDialog(
            onDismissRequest = { showUntrustedConfirm = false },
            title = { Text(stringResource(R.string.untrusted_files_detected)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "This content includes files outside the trusted set. Review and confirm to proceed.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    untrustedFiles.forEach { cf ->
                        Text(text = "- ${cf.target}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val profile = pendingProfile ?: return@TextButton
                    showUntrustedConfirm = false
                    isBusy = true
                    scope.launch {
                        performFinishInstall(ctx, mgr, profile) { _ ->
                            pendingProfile = null
                            currentType = profile.type
                            refreshInstalled()
                            statusMessage = null
                            isBusy = false
                        }
                    }
                }) { Text(stringResource(R.string.install_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { showUntrustedConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Delete confirmation
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.remove_content)) },
            text = { Text(stringResource(R.string.remove_content_confirmation, target.verName, target.verCode)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { mgr.removeContent(target) }
                        refreshInstalled()
                        Toast.makeText(ctx, "Removed ${target.verName}", Toast.LENGTH_SHORT).show()
                        deleteTarget = null
                    }
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(value = value, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
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
                        ContentsManager.InstallFailedReason.ERROR_EXIST -> "Content already exists"
                        ContentsManager.InstallFailedReason.ERROR_NOSPACE -> "Not enough space"
                        else -> "Failed to install content"
                    }
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    message = "Content installed successfully"
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


