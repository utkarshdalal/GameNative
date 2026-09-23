package app.gamenative.ui.screen.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.texturepack.TextureCacheUsage
import app.gamenative.texturepack.TexturePackGate
import app.gamenative.texturepack.TexturePackPaths
import app.gamenative.texturepack.TexturePackSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupPerformance() {
    val context = LocalContext.current
    val texturePackAvailable = remember { TexturePackGate.needsTexturePack(context) }
    if (texturePackAvailable) {
        SettingsGroup(
            modifier = Modifier.background(Color.Transparent),
            title = { Text(text = stringResource(R.string.settings_texture_section_title)) },
        ) {
            var texturePackEnabled by rememberSaveable { mutableStateOf(PrefManager.texturePackEnabled) }
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                state = texturePackEnabled,
                title = { Text(stringResource(R.string.settings_texture_pack_title)) },
                subtitle = { Text(stringResource(R.string.settings_texture_pack_subtitle)) },
                onCheckedChange = {
                    texturePackEnabled = it
                    PrefManager.texturePackEnabled = it
                },
            )

            var texturePackGpuTranscode by rememberSaveable { mutableStateOf(PrefManager.texturePackGpuTranscode) }
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                enabled = texturePackEnabled,
                state = texturePackGpuTranscode,
                title = { Text(stringResource(R.string.settings_texture_pack_gpu_transcode_title)) },
                subtitle = { Text(stringResource(R.string.settings_texture_pack_gpu_transcode_subtitle)) },
                onCheckedChange = {
                    texturePackGpuTranscode = it
                    PrefManager.texturePackGpuTranscode = it
                },
            )

            var texturePackAllowMobileData by rememberSaveable { mutableStateOf(PrefManager.texturePackAllowMobileData) }
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                state = texturePackAllowMobileData,
                title = { Text(stringResource(R.string.settings_texture_pack_mobile_data_title)) },
                subtitle = { Text(stringResource(R.string.settings_texture_pack_mobile_data_subtitle)) },
                onCheckedChange = {
                    texturePackAllowMobileData = it
                    PrefManager.texturePackAllowMobileData = it
                    TexturePackSyncWorker.schedule(context)
                },
            )

            TextureCacheSetting()

            var texturePackServer by rememberSaveable { mutableStateOf(PrefManager.texturePackServer) }
            var editingServer by rememberSaveable { mutableStateOf(false) }
            var serverDraft by rememberSaveable { mutableStateOf(texturePackServer) }
            SettingsMenuLink(
                colors = settingsTileColorsAlt(),
                title = { Text(stringResource(R.string.settings_texture_pack_server_title)) },
                subtitle = { Text(texturePackServer) },
                onClick = {
                    serverDraft = texturePackServer
                    editingServer = true
                },
            )

            if (editingServer) {
                AlertDialog(
                    onDismissRequest = { editingServer = false },
                    title = { Text(stringResource(R.string.settings_texture_pack_server_title)) },
                    text = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = serverDraft,
                            onValueChange = { serverDraft = it },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val value = serverDraft.trim().ifBlank { PrefManager.DEFAULT_TEXTURE_PACK_SERVER }
                            PrefManager.texturePackServer = value
                            texturePackServer = value
                            editingServer = false
                        }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            PrefManager.texturePackServer = PrefManager.DEFAULT_TEXTURE_PACK_SERVER
                            texturePackServer = PrefManager.DEFAULT_TEXTURE_PACK_SERVER
                            editingServer = false
                        }) {
                            Text(stringResource(R.string.settings_texture_pack_server_reset))
                        }
                    },
                )
            }
        }
    }
    SettingsGroup {
        var powerControlDefaultEnabled by rememberSaveable { mutableStateOf(PrefManager.powerControlDefaultEnabled) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = powerControlDefaultEnabled,
            title = { Text(stringResource(R.string.settings_performance_power_control_title)) },
            subtitle = { Text(stringResource(R.string.settings_performance_power_control_subtitle)) },
            onCheckedChange = {
                powerControlDefaultEnabled = it
                PrefManager.powerControlDefaultEnabled = it
            },
        )
    }
}

@Composable
private fun TextureCacheSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<List<TextureCacheUsage>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        usage = withContext(Dispatchers.IO) { TexturePackPaths.cacheUsage(context) }
    }

    val current = usage
    SettingsMenuLink(
        colors = settingsTileColorsAlt(),
        title = { Text(stringResource(R.string.settings_texture_cache_title)) },
        subtitle = {
            Text(
                if (current == null) {
                    stringResource(R.string.settings_texture_cache_calculating)
                } else {
                    Formatter.formatShortFileSize(context, current.sumOf { it.bytes })
                },
            )
        },
        onClick = { showDialog = true },
    )

    if (!showDialog) return

    val deleteDirs: (List<TextureCacheUsage>) -> Unit = { targets ->
        if (!deleting) {
            deleting = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    targets.forEach {
                        TexturePackPaths.clear(it.dir)
                        TexturePackGate.resetServerEntries(context, it.appId)
                    }
                }
                deleting = false
                usage = null
                refresh++
            }
        }
    }

    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(stringResource(R.string.settings_texture_cache_title)) },
        text = {
            when {
                current == null -> Text(stringResource(R.string.settings_texture_cache_calculating))
                current.isEmpty() -> Text(stringResource(R.string.settings_texture_cache_empty))
                else -> Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    current.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = Formatter.formatShortFileSize(context, entry.bytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(enabled = !deleting, onClick = { deleteDirs(listOf(entry)) }) {
                                Text(stringResource(R.string.settings_texture_cache_delete))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting && !current.isNullOrEmpty(),
                onClick = { current?.let(deleteDirs) },
            ) {
                Text(stringResource(R.string.settings_texture_cache_delete_all))
            }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }) {
                Text(stringResource(R.string.settings_texture_cache_close))
            }
        },
    )
}
