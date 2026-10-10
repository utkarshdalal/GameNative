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
import app.gamenative.api.SupportPatch
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.DebugRunParams
import app.gamenative.utils.DebugRunParamsHolder
import kotlinx.coroutines.launch

private enum class PatchConfirm { APPLY, APPLY_AND_RUN, RESTORE }

private sealed class PatchStatus {
    data object Applied : PatchStatus()
    data object Restored : PatchStatus()
    data class ApplyProblem(val result: SupportPatchApplier.Result) : PatchStatus()
    data class RestoreProblem(val result: SupportPatchApplier.Result) : PatchStatus()
}

@Composable
private fun PatchConfirmDialog(
    kind: PatchConfirm,
    run: DebugRunParams?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val restore = kind == PatchConfirm.RESTORE
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
                        if (restore) R.string.support_patch_restore_confirm_title else R.string.support_patch_confirm_title,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = stringResource(
                        if (restore) R.string.support_patch_restore_confirm_message else R.string.support_patch_confirm_message,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (kind == PatchConfirm.APPLY_AND_RUN) {
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
                            if (restore) R.string.support_suggestion_restore_confirm else R.string.support_suggestion_confirm,
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
private fun stepText(step: SupportPatchApplier.Step, label: String): String = stringResource(
    when (step) {
        SupportPatchApplier.Step.DOWNLOAD -> R.string.support_patch_downloading
        SupportPatchApplier.Step.VERIFY -> R.string.support_patch_verifying
        SupportPatchApplier.Step.BACKUP -> R.string.support_patch_backing_up
        SupportPatchApplier.Step.REPLACE -> R.string.support_patch_replacing
        SupportPatchApplier.Step.RESTORE -> R.string.support_patch_restoring
    },
    label,
)

@Composable
private fun problemText(result: SupportPatchApplier.Result, restore: Boolean): String = when (result) {
    SupportPatchApplier.Result.NoGame -> stringResource(R.string.support_patch_no_game)
    is SupportPatchApplier.Result.HashMismatch -> stringResource(R.string.support_patch_hash_mismatch, result.path)
    is SupportPatchApplier.Result.Failed -> stringResource(
        if (restore) R.string.support_patch_restore_failed else R.string.support_patch_failed,
        result.message,
    ).trim()
    SupportPatchApplier.Result.Done -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PatchCard(
    message: SupportApi.Message,
    patch: SupportPatch,
    appId: String?,
    conversationId: String,
    onStartDebugRun: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(message.id) { mutableStateOf<SupportPatchApplier.State?>(null) }
    var loaded by remember(message.id) { mutableStateOf(false) }
    var busy by remember(message.id) { mutableStateOf(false) }
    var progress by remember(message.id) { mutableStateOf<Triple<SupportPatchApplier.Step, Float, String>?>(null) }
    var status by remember(message.id) { mutableStateOf<PatchStatus?>(null) }
    var confirm by remember(message.id) { mutableStateOf<PatchConfirm?>(null) }
    var refresh by remember(message.id) { mutableStateOf(0) }

    LaunchedEffect(message.id, appId, refresh) {
        state = if (appId == null) null else SupportPatchApplier.readState(context, appId, patch)
        loaded = true
    }

    val run = patch.run
    val offersRun = patch.rerun || run != null
    val hasGame = appId != null && state != null
    val canApply = patch.applicable && hasGame && !busy &&
        (state == SupportPatchApplier.State.NONE || state == SupportPatchApplier.State.RESTORED)
    val canRestore = hasGame && !busy && state == SupportPatchApplier.State.APPLIED

    fun startRun(targetAppId: String) {
        DebugRunParamsHolder.set(targetAppId, run)
        SupportSession.startedRunFrom(targetAppId, conversationId)
        onStartDebugRun(targetAppId)
    }

    fun perform(kind: PatchConfirm) {
        val targetAppId = appId ?: return
        busy = true
        status = null
        scope.launch {
            val onProgress: (SupportPatchApplier.Step, Float, String) -> Unit = { step, value, label ->
                progress = Triple(step, value, label)
            }
            val restore = kind == PatchConfirm.RESTORE
            val result = if (restore) {
                SupportPatchApplier.restore(context, targetAppId, conversationId, patch, onProgress)
            } else {
                SupportPatchApplier.apply(context, targetAppId, conversationId, patch, onProgress)
            }
            progress = null
            busy = false
            refresh++
            if (result == SupportPatchApplier.Result.Done) {
                status = if (restore) PatchStatus.Restored else PatchStatus.Applied
                if (kind == PatchConfirm.APPLY_AND_RUN) startRun(targetAppId)
            } else {
                status = if (restore) PatchStatus.RestoreProblem(result) else PatchStatus.ApplyProblem(result)
            }
        }
    }

    confirm?.let { kind ->
        PatchConfirmDialog(
            kind = kind,
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
                    text = stringResource(R.string.support_patch_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            patch.summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!patch.applicable) {
                Text(
                    text = stringResource(R.string.support_patch_not_applicable),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else if (loaded && !hasGame) {
                Text(
                    text = stringResource(R.string.support_patch_no_game),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            patch.ops.forEach { op ->
                Text(
                    text = if (op.size >= 0) {
                        stringResource(R.string.support_files_size, op.path, Formatter.formatShortFileSize(context, op.size))
                    } else {
                        op.path
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
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
            progress?.let { (step, value, label) ->
                Text(
                    text = stepText(step, label),
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
                state == SupportPatchApplier.State.APPLIED -> stringResource(R.string.support_patch_applied)
                state == SupportPatchApplier.State.RESTORED -> stringResource(R.string.support_patch_restored)
                state == SupportPatchApplier.State.GAME_UPDATED -> stringResource(R.string.support_patch_game_updated)
                else -> null
            }
            stateText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == SupportPatchApplier.State.GAME_UPDATED) PluviaTheme.colors.accentWarning else PluviaTheme.colors.accentSuccess,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            current?.let {
                val text = when (it) {
                    PatchStatus.Applied -> stringResource(R.string.support_patch_applied)
                    PatchStatus.Restored -> stringResource(R.string.support_patch_restored)
                    is PatchStatus.ApplyProblem -> problemText(it.result, restore = false)
                    is PatchStatus.RestoreProblem -> problemText(it.result, restore = true)
                }
                val problem = it is PatchStatus.ApplyProblem || it is PatchStatus.RestoreProblem
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
                if (state != SupportPatchApplier.State.APPLIED) {
                    FocusableButton(
                        text = stringResource(if (offersRun) R.string.support_patch_apply_and_run else R.string.support_patch_apply),
                        onClick = { confirm = if (offersRun) PatchConfirm.APPLY_AND_RUN else PatchConfirm.APPLY },
                        enabled = canApply,
                    )
                }
                if (state == SupportPatchApplier.State.APPLIED) {
                    OutlinedFocusButton(
                        text = stringResource(R.string.support_patch_restore),
                        enabled = canRestore,
                        onClick = { confirm = PatchConfirm.RESTORE },
                    )
                }
            }
        }
    }
}
