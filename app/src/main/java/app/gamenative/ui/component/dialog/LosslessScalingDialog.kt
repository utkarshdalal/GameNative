package app.gamenative.ui.component.dialog

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.LsfgVkManager
import com.winlator.renderer.lsfg.LosslessScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
fun LosslessScalingDialog(
    openDialog: Boolean,
    onDismiss: () -> Unit,
    onInstallSuccess: () -> Unit = {},
) {
    if (!openDialog) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var isLocating by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    val dllFile = remember(refreshKey) { LosslessScaling.getDllFile(context) }
    val isDllImported = remember(refreshKey) { dllFile.isFile }
    val isGpuSupported = remember { LosslessScaling.isSupportedByGpu(context) }
    val isLoggedIn = SteamService.isLoggedIn
    val ownsApp = LsfgVkManager.ownsLosslessScaling()
    val hasLicense = isLoggedIn && ownsApp
    val variant = remember(refreshKey) {
        if (isDllImported) LosslessScaling.getVariant(context, true) else LosslessScaling.VARIANT_NONE
    }

    LaunchedEffect(refreshKey) {
        val active = SteamService.getAppDownloadInfo(LsfgVkManager.LOSSLESS_SCALING_APP_ID)
        if (active != null) {
            isDownloading = true
            downloadProgress = active.getProgress().coerceIn(0f, 1f)
            val listener: (Float) -> Unit = { progress ->
                downloadProgress = progress.coerceIn(0f, 1f)
            }
            active.addProgressListener(listener)
            try {
                withContext(Dispatchers.IO) {
                    active.awaitCompletion(timeoutMs = 7L * 24L * 60L * 60L * 1000L)
                }
            } finally {
                active.removeProgressListener(listener)
                isDownloading = false
                val steamDll = withContext(Dispatchers.IO) { LsfgVkManager.findSteamDll() }
                if (steamDll != null) {
                    val res = withContext(Dispatchers.IO) {
                        LosslessScaling.installFrom(context, steamDll)
                    }
                    if (res == LosslessScaling.STATUS_OK) {
                        SnackbarManager.show(context.getString(R.string.settings_lsfg_import_success))
                        onInstallSuccess()
                    }
                }
                refreshKey++
            }
        }
    }

    val isBusy = isDownloading || isLocating

    val openSteamStore = {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://store.steampowered.com/app/${LsfgVkManager.LOSSLESS_SCALING_APP_ID}/"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open Steam store link")
        }
    }

    fun startInstall() {
        scope.launch {
            if (!SteamService.isAppInstalled(LsfgVkManager.LOSSLESS_SCALING_APP_ID)) {
                val downloadInfo = SteamService.downloadApp(LsfgVkManager.LOSSLESS_SCALING_APP_ID)
                if (downloadInfo != null) {
                    isDownloading = true
                    downloadProgress = downloadInfo.getProgress().coerceIn(0f, 1f)
                    val listener: (Float) -> Unit = { progress ->
                        downloadProgress = progress.coerceIn(0f, 1f)
                    }
                    downloadInfo.addProgressListener(listener)
                    try {
                        withContext(Dispatchers.IO) {
                            downloadInfo.awaitCompletion(timeoutMs = 7L * 24L * 60L * 60L * 1000L)
                        }
                    } finally {
                        downloadInfo.removeProgressListener(listener)
                        isDownloading = false
                    }
                } else {
                    if (!SteamService.isAppInstalled(LsfgVkManager.LOSSLESS_SCALING_APP_ID)) {
                        SnackbarManager.show(context.getString(R.string.download_failed_try_again))
                        return@launch
                    }
                }
            }

            if (!SteamService.isAppInstalled(LsfgVkManager.LOSSLESS_SCALING_APP_ID)) {
                SnackbarManager.show(context.getString(R.string.download_failed_try_again))
                return@launch
            }

            isLocating = true
            try {
                var steamDll = withContext(Dispatchers.IO) { LsfgVkManager.findSteamDll() }
                if (steamDll != null) {
                    val res = withContext(Dispatchers.IO) {
                        LosslessScaling.installFrom(context, steamDll)
                    }
                    if (res == LosslessScaling.STATUS_OK) {
                        SnackbarManager.show(context.getString(R.string.settings_lsfg_import_success))
                        refreshKey++
                        onInstallSuccess()
                    } else {
                        SnackbarManager.show(context.getString(R.string.settings_lsfg_import_failed))
                    }
                } else {
                    SnackbarManager.show(context.getString(R.string.settings_lsfg_dll_not_found))
                }
            } finally {
                isLocating = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isBusy) onDismiss()
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = null,
                tint = PluviaTheme.colors.accentPurple,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                text = stringResource(R.string.settings_lsfg_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_lsfg_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!hasLicense) {
                    val ownershipError = if (!isLoggedIn) {
                        stringResource(R.string.library_source_not_logged_in_steam)
                    } else {
                        stringResource(R.string.lsfg_not_in_library)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = ownershipError,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PluviaTheme.colors.accentSuccess.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = PluviaTheme.colors.accentSuccess,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.settings_lsfg_license_verified),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = PluviaTheme.colors.accentSuccess,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (isDllImported) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isDllImported) PluviaTheme.colors.accentSuccess else PluviaTheme.colors.accentWarning,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = if (isDllImported) stringResource(R.string.settings_lsfg_status_installed)
                                           else stringResource(R.string.settings_lsfg_status_not_installed),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isDllImported) PluviaTheme.colors.accentSuccess else PluviaTheme.colors.accentWarning,
                                )
                            }

                            if (isDllImported) {
                                val variantLabel = when (variant) {
                                    LosslessScaling.VARIANT_FP16 -> "FP16 SPIR-V"
                                    LosslessScaling.VARIANT_FP32 -> "FP32 SPIR-V"
                                    LosslessScaling.VARIANT_DXBC -> "DXBC"
                                    else -> "Native"
                                }
                                Text(
                                    text = "Shader Variant: $variantLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "File Size: ${dllFile.length() / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.settings_lsfg_ready_to_install),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (isDllImported) {
                            OutlinedButton(
                                onClick = {
                                    LosslessScaling.remove(context)
                                    refreshKey++
                                },
                                enabled = !isBusy,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.settings_lsfg_remove_btn),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                // One-Button Install / Action Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isBusy) {
                            if (isDownloading) {
                                val pct = (downloadProgress * 100).toInt()
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = PluviaTheme.colors.accentPurple,
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_lsfg_step1_downloading, pct),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PluviaTheme.colors.accentPurple,
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = PluviaTheme.colors.accentPurple,
                                    )
                                }
                            } else if (isLocating) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = PluviaTheme.colors.accentPurple,
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_lsfg_step2_locating),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PluviaTheme.colors.accentPurple,
                                    )
                                }
                            }
                        } else if (isDllImported) {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = PluviaTheme.colors.accentSuccess.copy(alpha = 0.18f),
                                    disabledContentColor = PluviaTheme.colors.accentSuccess,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = PluviaTheme.colors.accentSuccess,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.settings_lsfg_installed_btn),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = PluviaTheme.colors.accentSuccess,
                                )
                            }
                        } else if (hasLicense) {
                            Button(
                                onClick = { startInstall() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PluviaTheme.colors.accentPurple),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_lsfg_install_btn),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                )
                            }
                        } else if (!isLoggedIn) {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.settings_lsfg_sign_in_steam))
                            }
                        } else {
                            Button(
                                onClick = openSteamStore,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PluviaTheme.colors.accentPurple),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_lsfg_buy_steam_btn),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (isGpuSupported) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isGpuSupported) PluviaTheme.colors.accentSuccess else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isGpuSupported) stringResource(R.string.settings_lsfg_gpu_supported)
                               else stringResource(R.string.settings_lsfg_gpu_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isGpuSupported) PluviaTheme.colors.textMuted else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isBusy,
            ) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
}
