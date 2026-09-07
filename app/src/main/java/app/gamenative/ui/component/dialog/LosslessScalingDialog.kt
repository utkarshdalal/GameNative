package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import com.winlator.renderer.lsfg.LosslessScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LosslessScalingDialog(
    openDialog: Boolean,
    onDismiss: () -> Unit,
) {
    if (!openDialog) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }

    val dllFile = remember(refreshKey) { LosslessScaling.getDllFile(context) }
    val isInstalled = remember(refreshKey) { dllFile.isFile }
    val isGpuSupported = remember { LosslessScaling.isSupportedByGpu(context) }
    val variant = remember(refreshKey) {
        if (isInstalled) LosslessScaling.getVariant(context, true) else LosslessScaling.VARIANT_NONE
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LosslessScaling.installFrom(context, uri)
            }
            isImporting = false
            if (result == LosslessScaling.STATUS_OK) {
                SnackbarManager.show(context.getString(R.string.settings_lsfg_import_success))
                refreshKey++
            } else {
                SnackbarManager.show(context.getString(R.string.settings_lsfg_import_failed))
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isImporting) onDismiss()
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
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_lsfg_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Status card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isInstalled) PluviaTheme.colors.accentSuccess else PluviaTheme.colors.accentWarning,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = if (isInstalled) stringResource(R.string.settings_lsfg_status_installed)
                                       else stringResource(R.string.settings_lsfg_status_not_installed),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isInstalled) PluviaTheme.colors.accentSuccess else PluviaTheme.colors.accentWarning,
                            )
                        }

                        if (isInstalled) {
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
                                text = stringResource(R.string.settings_lsfg_how_to_get),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Import / Replace / Remove buttons
                if (isImporting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = PluviaTheme.colors.accentPurple,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_frame_generation_importing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = PluviaTheme.colors.accentPurple),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isInstalled) stringResource(R.string.settings_lsfg_replace_btn)
                                       else stringResource(R.string.settings_lsfg_import_btn),
                                maxLines = 1,
                            )
                        }

                        if (isInstalled) {
                            OutlinedButton(
                                onClick = {
                                    LosslessScaling.remove(context)
                                    refreshKey++
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                // GPU Compatibility note
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
                enabled = !isImporting,
            ) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
}
