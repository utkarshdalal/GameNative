package app.gamenative.ui.screen.support

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.R
import app.gamenative.api.SupportApi
import app.gamenative.api.SupportSuggestion
import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.ui.component.dialog.winComponentsItemTitleRes
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.DebugRunParams
import app.gamenative.utils.DebugRunParamsHolder
import kotlinx.coroutines.launch

private enum class SuggestionConfirm { APPLY, APPLY_AND_RUN, RESTORE }

private sealed class SuggestionStatus {
    data object Applied : SuggestionStatus()
    data object Restored : SuggestionStatus()
    data class Problem(val result: SupportSuggestionApplier.Result) : SuggestionStatus()
}

private val TOP_LEVEL_LABELS: Map<String, Int> = mapOf(
    "executablePath" to R.string.executable_path,
    "graphicsDriver" to R.string.graphics_driver,
    "graphicsDriverVersion" to R.string.graphics_driver_version,
    "dxwrapper" to R.string.dx_wrapper,
    "execArgs" to R.string.exec_arguments,
    "startupSelection" to R.string.startup_selection,
    "box64Version" to R.string.box64_version,
    "box64Preset" to R.string.box64_preset,
    "wineVersion" to R.string.wine_version,
    "emulator" to R.string.emulator_64bit,
    "fexcoreVersion" to R.string.fexcore_version,
    "fexcorePreset" to R.string.fexcore_preset,
    "useLegacyDRM" to R.string.use_legacy_drm,
    "steamOfflineMode" to R.string.steam_offline_mode,
    "loadMods" to R.string.load_mods,
    "epicOfflineMode" to R.string.epic_offline_mode,
    "unpackFiles" to R.string.unpack_files,
    "suspendPolicy" to R.string.suspend_behavior,
    "cpuList" to R.string.processor_affinity,
    "cpuListWoW64" to R.string.processor_affinity_32bit,
    "audioDriver" to R.string.audio_driver,
    "videoMemorySize" to R.string.video_memory_size,
    "launchBionicSteam" to R.string.launch_bionic_steam,
    "launchRealSteam" to R.string.launch_steam_client_beta,
    "steamType" to R.string.steam_type,
)

private val DXWRAPPER_LABELS: Map<String, Int> = mapOf(
    "version" to R.string.dxvk_version,
    "vkd3dLevel" to R.string.vkd3d_feature_level,
    "maxDeviceMemory" to R.string.max_device_memory,
    "csmt" to R.string.enable_csmt,
    "gpuName" to R.string.gpu_name,
    "videoMemorySize" to R.string.video_memory_size,
    "strict_shader_math" to R.string.enable_strict_shader_math,
    "OffscreenRenderingMode" to R.string.offscreen_rendering_mode,
    "renderer" to R.string.renderer,
)

private val DRIVER_LABELS: Map<String, Int> = mapOf(
    "version" to R.string.graphics_driver_version,
    "vkMaxVersion" to R.string.vulkan_version,
    "presentMode" to R.string.present_modes,
    "maxDeviceMemory" to R.string.max_device_memory,
    "adrenotoolsTurnip" to R.string.use_adrenotools_turnip,
    "bcnEmulation" to R.string.bcn_emulation,
    "bcnEmulationType" to R.string.bcn_emulation_type,
    "imageCacheSize" to R.string.image_cache_size,
    "resourceType" to R.string.resource_type,
    "transcoder" to R.string.transcoder,
    "quality" to R.string.wrapper_quality,
    "exposedDeviceExtensions" to R.string.exposed_vulkan_extensions,
)

@Composable
private fun changeLabel(change: SupportSuggestion.Change): String {
    if (change.isEnv) return stringResource(R.string.environment_variables) + ": " + (change.name ?: "")
    val subKey = change.subKey
    if (subKey == null) return TOP_LEVEL_LABELS[change.key]?.let { stringResource(it) } ?: change.key
    return when (change.parent) {
        "wincomponents" -> if (subKey in SupportSuggestion.WIN_COMPONENTS) {
            stringResource(winComponentsItemTitleRes(subKey))
        } else {
            subKey
        }
        "dxwrapperConfig" -> DXWRAPPER_LABELS[subKey]?.let { stringResource(it) }
            ?: (stringResource(R.string.dx_wrapper) + " ($subKey)")
        "graphicsDriverConfig" -> DRIVER_LABELS[subKey]?.let { stringResource(it) }
            ?: (stringResource(R.string.graphics_driver) + " ($subKey)")
        else -> change.key
    }
}

private fun booleanOf(value: String): Boolean? = when (value.trim().lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

private fun sameValue(change: SupportSuggestion.Change, a: String?, b: String?): Boolean {
    if (a == null || b == null) return a == b
    if (change.subKey == null && !change.isEnv &&
        SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS[change.key] == SuggestionConfigKeys.ConfigValueType.BOOLEAN
    ) {
        return booleanOf(a) == booleanOf(b)
    }
    return a.trim() == b.trim()
}

@Composable
private fun displayValue(change: SupportSuggestion.Change, value: String?): String {
    if (value == null) return stringResource(R.string.not_set)
    if (change.parent == "wincomponents" && change.subKey != null) {
        val entries = stringArrayResource(R.array.win_component_entries)
        value.toIntOrNull()?.let { entries.getOrNull(it) }?.let { return it }
    }
    if (change.subKey == null && !change.isEnv) {
        if (change.key == "startupSelection") {
            val entries = stringArrayResource(R.array.startup_selection_entries)
            value.toIntOrNull()?.let { entries.getOrNull(it) }?.let { return it }
        }
        if (SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS[change.key] == SuggestionConfigKeys.ConfigValueType.BOOLEAN) {
            booleanOf(value)?.let { return stringResource(if (it) R.string.enabled else R.string.disabled) }
        }
    }
    return value.ifEmpty { stringResource(R.string.not_set) }
}

@Composable
private fun runRecordText(run: DebugRunParams): String? {
    val parts = buildList {
        if (run.winedebug.isNotEmpty()) {
            add(stringResource(R.string.support_suggestion_run_channels, run.winedebug.joinToString(", ")))
        }
        if (run.env.isNotEmpty()) {
            add(stringResource(R.string.support_suggestion_run_env, run.env.entries.joinToString(", ") { "${it.key}=${it.value}" }))
        }
        if (DebugRunParams.ATTACH_WRAPPER_DIAG in run.attach) {
            add(stringResource(R.string.support_suggestion_run_wrapper_diag))
        }
    }
    if (parts.isEmpty()) return null
    return stringResource(R.string.support_suggestion_run_record, parts.joinToString("; "))
}

@Composable
private fun OutlinedFocusButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        modifier = modifier.focusRing(interaction, shape, width = 2.dp),
    ) {
        Text(text = text)
    }
}

@Composable
private fun SuggestionConfirmDialog(
    kind: SuggestionConfirm,
    showWarning: Boolean,
    run: DebugRunParams?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = PluviaTheme.colors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = stringResource(
                        if (kind == SuggestionConfirm.RESTORE) {
                            R.string.support_suggestion_restore_confirm_title
                        } else {
                            R.string.support_suggestion_confirm_title
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = stringResource(
                        if (kind == SuggestionConfirm.RESTORE) {
                            R.string.support_suggestion_restore_confirm_message
                        } else {
                            R.string.support_suggestion_confirm_message
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (kind == SuggestionConfirm.APPLY_AND_RUN) {
                    Text(
                        text = stringResource(R.string.support_suggestion_confirm_run),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    run?.instruction?.let {
                        Text(
                            text = stringResource(R.string.debug_prerun_instruction, it),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    run?.minSeconds?.let { seconds ->
                        val minutes = (seconds + 59) / 60
                        Text(
                            text = pluralStringResource(R.plurals.debug_prerun_min_minutes, minutes, minutes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                if (showWarning && kind != SuggestionConfirm.RESTORE) {
                    Text(
                        text = stringResource(R.string.support_suggestion_confirm_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                val confirmFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedFocusButton(
                        text = stringResource(R.string.cancel),
                        enabled = true,
                        onClick = onDismiss,
                    )
                    FocusableButton(
                        text = stringResource(
                            if (kind == SuggestionConfirm.RESTORE) {
                                R.string.support_suggestion_restore_confirm
                            } else {
                                R.string.support_suggestion_confirm
                            },
                        ),
                        onClick = onConfirm,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .focusRequester(confirmFocus),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SuggestionCard(
    message: SupportApi.Message,
    suggestion: SupportSuggestion,
    appId: String?,
    conversationId: String,
    onStartDebugRun: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var live by remember(message.id) { mutableStateOf<List<String?>?>(null) }
    var hasContainer by remember(message.id) { mutableStateOf(true) }
    var hasSnapshot by remember(message.id) { mutableStateOf(false) }
    var busy by remember(message.id) { mutableStateOf(false) }
    var progress by remember(message.id) { mutableStateOf<Pair<Float, String>?>(null) }
    var status by remember(message.id) { mutableStateOf<SuggestionStatus?>(null) }
    var confirm by remember(message.id) { mutableStateOf<SuggestionConfirm?>(null) }
    var refresh by remember(message.id) { mutableStateOf(0) }

    LaunchedEffect(message.id, appId, refresh) {
        if (appId == null) {
            hasContainer = false
            return@LaunchedEffect
        }
        val values = SupportSuggestionApplier.readLive(context, appId, suggestion)
        hasContainer = values != null
        live = values
        hasSnapshot = SupportSuggestionApplier.hasSnapshot(context, appId, message.id)
    }

    val canAct = suggestion.applicable && appId != null && hasContainer && !busy
    val hasChanges = suggestion.changes.isNotEmpty()
    val run = suggestion.run
    val offersRun = suggestion.rerun || run != null

    fun startRun(targetAppId: String) {
        DebugRunParamsHolder.set(targetAppId, run)
        SupportSession.startedRunFrom(targetAppId, conversationId)
        onStartDebugRun(targetAppId)
    }

    fun perform(kind: SuggestionConfirm) {
        val targetAppId = appId ?: return
        busy = true
        status = null
        scope.launch {
            val onProgress: (Float, String) -> Unit = { value, label -> progress = value to label }
            val result = if (kind == SuggestionConfirm.RESTORE) {
                SupportSuggestionApplier.restore(context, targetAppId, message.id, conversationId, onProgress)
            } else {
                SupportSuggestionApplier.apply(context, targetAppId, message.id, conversationId, suggestion, onProgress)
            }
            progress = null
            busy = false
            refresh++
            if (result == SupportSuggestionApplier.Result.Done) {
                status = if (kind == SuggestionConfirm.RESTORE) SuggestionStatus.Restored else SuggestionStatus.Applied
                if (kind == SuggestionConfirm.APPLY_AND_RUN) startRun(targetAppId)
            } else {
                status = SuggestionStatus.Problem(result)
            }
        }
    }

    confirm?.let { kind ->
        SuggestionConfirmDialog(
            kind = kind,
            showWarning = suggestion.changes.any { it.parent in SupportSuggestionApplier.WARNING_KEYS },
            run = run,
            onConfirm = {
                confirm = null
                perform(kind)
            },
            onDismiss = { confirm = null },
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PluviaTheme.colors.surfaceElevated,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, PluviaTheme.colors.accentPurple),
        modifier = Modifier
            .padding(top = 6.dp)
            .widthIn(max = 640.dp)
            .fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = PluviaTheme.colors.accentPurple,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.support_suggestion_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (!suggestion.applicable) {
                Text(
                    text = stringResource(R.string.support_suggestion_not_applicable),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else if (appId == null || !hasContainer) {
                Text(
                    text = stringResource(R.string.support_suggestion_no_game),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            suggestion.changes.forEachIndexed { index, change ->
                val before = live?.getOrNull(index)
                val after = if (change.isUnset) null else change.to
                Text(
                    text = stringResource(
                        R.string.support_suggestion_change,
                        changeLabel(change),
                        if (live == null) (change.from?.let { displayValue(change, it) } ?: "…") else displayValue(change, before),
                        displayValue(change, after),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                change.why?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                    )
                }
                val from = change.from
                if (live != null && from != null && !sameValue(change, before, from)) {
                    Text(
                        text = stringResource(
                            R.string.support_suggestion_changed_since,
                            displayValue(change, before),
                            displayValue(change, from),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.accentWarning,
                    )
                }
            }
            if (run != null) {
                runRecordText(run)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                run.instruction?.let {
                    Text(
                        text = stringResource(R.string.debug_prerun_instruction, it),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                run.minSeconds?.let { seconds ->
                    val minutes = (seconds + 59) / 60
                    Text(
                        text = pluralStringResource(R.plurals.debug_prerun_min_minutes, minutes, minutes),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            progress?.let { (value, label) ->
                Text(
                    text = stringResource(R.string.support_suggestion_installing, label),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (value < 0f) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else {
                    LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            status?.let { current ->
                val text = when (current) {
                    SuggestionStatus.Applied -> stringResource(R.string.support_suggestion_applied)
                    SuggestionStatus.Restored -> stringResource(R.string.support_suggestion_restored)
                    is SuggestionStatus.Problem -> when (val result = current.result) {
                        is SupportSuggestionApplier.Result.MissingComponents ->
                            stringResource(R.string.support_suggestion_missing, result.names.joinToString(", "))
                        SupportSuggestionApplier.Result.NoContainer -> stringResource(R.string.support_suggestion_no_game)
                        is SupportSuggestionApplier.Result.Failed ->
                            stringResource(R.string.support_suggestion_failed, result.message).trim()
                        SupportSuggestionApplier.Result.Done -> ""
                    }
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current is SuggestionStatus.Problem) PluviaTheme.colors.accentDanger else PluviaTheme.colors.accentSuccess,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasChanges) {
                    if (offersRun) {
                        FocusableButton(
                            text = stringResource(R.string.support_suggestion_apply_and_run),
                            onClick = { confirm = SuggestionConfirm.APPLY_AND_RUN },
                            enabled = canAct,
                        )
                        OutlinedFocusButton(
                            text = stringResource(R.string.support_suggestion_apply_only),
                            enabled = canAct,
                            onClick = { confirm = SuggestionConfirm.APPLY },
                        )
                    } else {
                        FocusableButton(
                            text = stringResource(R.string.support_suggestion_apply_only),
                            onClick = { confirm = SuggestionConfirm.APPLY },
                            enabled = canAct,
                        )
                    }
                } else if (offersRun) {
                    FocusableButton(
                        text = stringResource(R.string.support_new_debug_run),
                        onClick = { appId?.let { startRun(it) } },
                        enabled = canAct,
                    )
                }
                if (hasSnapshot) {
                    OutlinedFocusButton(
                        text = stringResource(R.string.support_suggestion_restore),
                        enabled = appId != null && !busy,
                        onClick = { confirm = SuggestionConfirm.RESTORE },
                    )
                }
            }
        }
    }
}
