package app.gamenative.ui.screen.support

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.R
import app.gamenative.api.SupportApi
import app.gamenative.api.SupportComponent
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.DebugRunParams
import app.gamenative.utils.DebugRunParamsHolder
import kotlinx.coroutines.launch

private enum class ComponentConfirm { INSTALL, INSTALL_AND_RUN, RESTORE }

private sealed class ComponentStatus {
    data object Applied : ComponentStatus()
    data object Restored : ComponentStatus()
    data class ApplyProblem(val result: SupportComponentApplier.Result) : ComponentStatus()
    data class RestoreProblem(val result: SupportComponentApplier.Result) : ComponentStatus()
}

private fun typeLabel(type: SupportComponent.Type): String = when (type) {
    SupportComponent.Type.TURNIP -> "Turnip"
    SupportComponent.Type.WRAPPER -> "Wrapper"
    SupportComponent.Type.FEXCORE -> "FEXCore"
    SupportComponent.Type.BOX64 -> "Box64"
    SupportComponent.Type.DXVK -> "DXVK"
    SupportComponent.Type.VKD3D -> "VKD3D-Proton"
    SupportComponent.Type.PROTON -> "Proton"
}

@Composable
private fun ComponentConfirmDialog(
    kind: ComponentConfirm,
    component: SupportComponent,
    run: DebugRunParams?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val restore = kind == ComponentConfirm.RESTORE
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
                    text = if (restore) {
                        stringResource(R.string.support_component_restore_confirm_title)
                    } else {
                        stringResource(R.string.support_component_confirm_title, typeLabel(component.type), component.versionName)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = stringResource(
                        if (restore) R.string.support_component_restore_confirm_message else R.string.support_component_confirm_message,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (kind == ComponentConfirm.INSTALL_AND_RUN) {
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
                            if (restore) R.string.support_suggestion_restore_confirm else R.string.support_component_install,
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

@Composable
private fun stepText(step: SupportComponentApplier.Step): String = stringResource(
    when (step) {
        SupportComponentApplier.Step.DOWNLOAD -> R.string.support_component_downloading
        SupportComponentApplier.Step.VERIFY -> R.string.support_component_verifying
        SupportComponentApplier.Step.IMPORT -> R.string.support_component_importing
        SupportComponentApplier.Step.APPLY -> R.string.support_component_applying
        SupportComponentApplier.Step.RESTORE -> R.string.support_component_restoring
    },
)

@Composable
private fun problemText(result: SupportComponentApplier.Result, restore: Boolean): String = when (result) {
    SupportComponentApplier.Result.NoContainer -> stringResource(R.string.support_component_no_container)
    SupportComponentApplier.Result.HashMismatch -> stringResource(R.string.support_component_hash_mismatch)
    is SupportComponentApplier.Result.Failed -> stringResource(
        if (restore) R.string.support_component_restore_failed else R.string.support_component_failed,
        result.message,
    ).trim()
    SupportComponentApplier.Result.Done -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComponentCard(
    message: SupportApi.Message,
    component: SupportComponent,
    appId: String?,
    conversationId: String,
    onStartDebugRun: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(message.id) { mutableStateOf<SupportComponentApplier.State?>(null) }
    var loaded by remember(message.id) { mutableStateOf(false) }
    var busy by remember(message.id) { mutableStateOf(false) }
    var progress by remember(message.id) { mutableStateOf<Pair<SupportComponentApplier.Step, Float>?>(null) }
    var status by remember(message.id) { mutableStateOf<ComponentStatus?>(null) }
    var confirm by remember(message.id) { mutableStateOf<ComponentConfirm?>(null) }
    var refresh by remember(message.id) { mutableStateOf(0) }

    LaunchedEffect(message.id, appId, refresh) {
        state = if (appId == null) null else SupportComponentApplier.readState(context, appId, component)
        loaded = true
    }

    val run = component.run
    val offersRun = component.rerun || run != null
    val hasGame = appId != null && state != null
    val canApply = hasGame && !busy &&
        (state == SupportComponentApplier.State.NONE || state == SupportComponentApplier.State.RESTORED)
    val canRestore = hasGame && !busy && state == SupportComponentApplier.State.APPLIED

    fun startRun(targetAppId: String) {
        DebugRunParamsHolder.set(targetAppId, run)
        SupportSession.startedRunFrom(targetAppId, conversationId)
        onStartDebugRun(targetAppId)
    }

    fun perform(kind: ComponentConfirm) {
        val targetAppId = appId ?: return
        busy = true
        status = null
        scope.launch {
            val onProgress: (SupportComponentApplier.Step, Float) -> Unit = { step, value ->
                progress = step to value
            }
            val restore = kind == ComponentConfirm.RESTORE
            val result = if (restore) {
                SupportComponentApplier.restore(context, targetAppId, conversationId, component, onProgress)
            } else {
                SupportComponentApplier.apply(context, targetAppId, conversationId, component, onProgress)
            }
            progress = null
            busy = false
            refresh++
            if (result == SupportComponentApplier.Result.Done) {
                status = if (restore) ComponentStatus.Restored else ComponentStatus.Applied
                if (kind == ComponentConfirm.INSTALL_AND_RUN) startRun(targetAppId)
            } else {
                status = if (restore) ComponentStatus.RestoreProblem(result) else ComponentStatus.ApplyProblem(result)
            }
        }
    }

    confirm?.let { kind ->
        ComponentConfirmDialog(
            kind = kind,
            component = component,
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
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = PluviaTheme.colors.accentPurple,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.support_component_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            component.summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (loaded && !hasGame) {
                Text(
                    text = stringResource(R.string.support_component_no_game),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                text = stringResource(
                    R.string.support_component_version,
                    typeLabel(component.type),
                    component.versionName,
                    Formatter.formatShortFileSize(context, component.size),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            component.sourceRepo?.let { repo ->
                Text(
                    text = component.sourceRef?.let { ref -> stringResource(R.string.support_component_source_ref, repo, ref) }
                        ?: stringResource(R.string.support_component_source, repo),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (run != null) {
                run.instruction?.let {
                    Text(
                        text = stringResource(R.string.debug_prerun_instruction, it),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp),
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
            progress?.let { (step, value) ->
                Text(
                    text = stepText(step),
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
            val current = status
            val stateText = when {
                current != null -> null
                state == SupportComponentApplier.State.APPLIED -> stringResource(R.string.support_component_applied)
                state == SupportComponentApplier.State.RESTORED -> stringResource(R.string.support_component_restored)
                else -> null
            }
            stateText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.accentSuccess,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            current?.let {
                val text = when (it) {
                    ComponentStatus.Applied -> stringResource(R.string.support_component_applied)
                    ComponentStatus.Restored -> stringResource(R.string.support_component_restored)
                    is ComponentStatus.ApplyProblem -> problemText(it.result, restore = false)
                    is ComponentStatus.RestoreProblem -> problemText(it.result, restore = true)
                }
                val problem = it is ComponentStatus.ApplyProblem || it is ComponentStatus.RestoreProblem
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem) PluviaTheme.colors.accentDanger else PluviaTheme.colors.accentSuccess,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state != SupportComponentApplier.State.APPLIED) {
                    FocusableButton(
                        text = stringResource(if (offersRun) R.string.support_component_install_and_run else R.string.support_component_install),
                        onClick = { confirm = if (offersRun) ComponentConfirm.INSTALL_AND_RUN else ComponentConfirm.INSTALL },
                        enabled = canApply,
                    )
                }
                if (state == SupportComponentApplier.State.APPLIED) {
                    OutlinedFocusButton(
                        text = stringResource(R.string.support_component_restore),
                        enabled = canRestore,
                        onClick = { confirm = ComponentConfirm.RESTORE },
                    )
                }
            }
        }
    }
}
