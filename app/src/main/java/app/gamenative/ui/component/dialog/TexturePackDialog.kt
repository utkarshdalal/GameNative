package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.gamenative.texturepack.TexturePackEstimate
import app.gamenative.texturepack.TexturePackGate
import app.gamenative.texturepack.TexturePackNetworkUnavailable
import app.gamenative.texturepack.TexturePackPhase
import app.gamenative.texturepack.TexturePackPreparer
import app.gamenative.texturepack.TexturePackProgress
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun TexturePackDialog(
    appId: String,
    platform: String,
    storeId: String,
    gameDir: File,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preparer = remember(appId, storeId) { TexturePackPreparer(context, appId, platform, storeId, gameDir) }

    var estimate by remember(appId) { mutableStateOf<TexturePackEstimate?>(null) }
    var statusError by remember(appId) { mutableStateOf<String?>(null) }
    var progress by remember(appId) { mutableStateOf<TexturePackProgress?>(null) }
    var dontAsk by remember(appId) { mutableStateOf(false) }
    var prepareJob by remember(appId) { mutableStateOf<Job?>(null) }

    LaunchedEffect(appId, storeId) {
        try {
            val result = preparer.estimate()
            estimate = result
            if (result is TexturePackEstimate.Unsupported) {
                TexturePackGate.setSkipped(context, appId, true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TexturePackNetworkUnavailable) {
            statusError = context.getString(R.string.texture_pack_status_wifi)
        } catch (e: Exception) {
            Timber.w(e, "texture pack estimate failed for $appId")
            statusError = context.getString(R.string.texture_pack_status_failed)
        }
    }

    val finish: () -> Unit = {
        if (dontAsk) TexturePackGate.setSkipped(context, appId, true)
        onPlay()
    }

    val current = estimate
    val running = progress != null
    val unsupported = current is TexturePackEstimate.Unsupported

    AlertDialog(
        onDismissRequest = {
            if (!running) {
                if (dontAsk) TexturePackGate.setSkipped(context, appId, true)
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.texture_pack_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.texture_pack_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = statusLine(
                        error = statusError,
                        estimate = current,
                        checking = stringResource(R.string.texture_pack_status_checking),
                        unsupportedText = stringResource(R.string.texture_pack_status_unsupported),
                        readyTemplate = stringResource(R.string.texture_pack_status_ready),
                        workTemplate = stringResource(R.string.texture_pack_status_work),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                progress?.let { state ->
                    Text(
                        text = phaseLabel(state.phase),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.total > 0L) {
                        LinearProgressIndicator(
                            progress = { (state.current.toFloat() / state.total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(
                                R.string.texture_pack_progress_bytes,
                                megabytes(state.current),
                                megabytes(state.total),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (!running && !unsupported) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontAsk = !dontAsk }
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                        Text(
                            text = stringResource(R.string.texture_pack_dont_ask),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                unsupported || statusError != null -> {
                    TextButton(onClick = finish) { Text(stringResource(R.string.texture_pack_play)) }
                }
                running -> {
                    TextButton(onClick = {
                        prepareJob?.cancel()
                        prepareJob = null
                        progress = null
                        finish()
                    }) {
                        Text(stringResource(R.string.texture_pack_cancel_and_play))
                    }
                }
                else -> {
                    TextButton(
                        enabled = current != null,
                        onClick = {
                            progress = TexturePackProgress(TexturePackPhase.SCANNING)
                            prepareJob = scope.launch {
                                try {
                                    preparer.prepare { progress = it }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Timber.w(e, "texture pack preparation failed for $appId")
                                }
                                progress = null
                                finish()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.texture_pack_prepare_and_play))
                    }
                }
            }
        },
        dismissButton = {
            if (!running && !unsupported && statusError == null) {
                TextButton(onClick = finish) { Text(stringResource(R.string.texture_pack_play_anyway)) }
            }
        },
    )
}

@Composable
private fun phaseLabel(phase: TexturePackPhase): String = stringResource(
    when (phase) {
        TexturePackPhase.SCANNING -> R.string.texture_pack_phase_scanning
        TexturePackPhase.HASHING -> R.string.texture_pack_phase_hashing
        TexturePackPhase.UPLOADING -> R.string.texture_pack_phase_uploading
        TexturePackPhase.WAITING -> R.string.texture_pack_phase_waiting
        TexturePackPhase.DOWNLOADING -> R.string.texture_pack_phase_downloading
        TexturePackPhase.DONE -> R.string.texture_pack_phase_done
    },
)

private fun statusLine(
    error: String?,
    estimate: TexturePackEstimate?,
    checking: String,
    unsupportedText: String,
    readyTemplate: String,
    workTemplate: String,
): String = when {
    error != null -> error
    estimate == null -> checking
    estimate is TexturePackEstimate.Unsupported -> unsupportedText
    estimate is TexturePackEstimate.Ready -> String.format(readyTemplate, megabytes(estimate.downloadBytes))
    estimate is TexturePackEstimate.Work ->
        String.format(workTemplate, megabytes(estimate.uploadBytes), megabytes(estimate.downloadBytes))
    else -> checking
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f", bytes.toDouble() / 1_000_000.0)
