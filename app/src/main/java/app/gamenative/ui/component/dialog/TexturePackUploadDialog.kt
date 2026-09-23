package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.texturepack.TexturePackClient
import app.gamenative.texturepack.TexturePackGate
import app.gamenative.texturepack.TexturePackPaths
import app.gamenative.texturepack.TexturePackSync
import app.gamenative.texturepack.TexturePackSyncWorker
import app.gamenative.texturepack.TexturePackUploadPrompt
import app.gamenative.utils.ContainerUtils
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
fun TexturePackUploadDialog() {
    val appId = TexturePackUploadPrompt.pendingAppId ?: return
    val context = LocalContext.current
    var total by remember(appId) { mutableStateOf(0L) }
    var done by remember(appId) { mutableStateOf(0L) }
    var visible by remember(appId) { mutableStateOf(false) }
    var dontAsk by remember(appId) { mutableStateOf(false) }

    LaunchedEffect(appId) {
        try {
            if (!ContainerUtils.hasContainer(context, appId) || !TexturePackGate.syncEnabled(context, appId)) {
                TexturePackUploadPrompt.clear()
                return@LaunchedEffect
            }
            val container = ContainerUtils.getContainer(context, appId)
            if (TexturePackGate.policyDisabled(TexturePackGate.policyOf(container))) {
                TexturePackUploadPrompt.clear()
                return@LaunchedEffect
            }
            val cacheDir = TexturePackPaths.cacheDir(container)
            val pending = withContext(Dispatchers.IO) {
                if (cacheDir.isDirectory) TexturePackSync.sourceFiles(cacheDir) else emptyList()
            }
            if (pending.isEmpty()) {
                TexturePackUploadPrompt.clear()
                return@LaunchedEffect
            }
            total = pending.sumOf { it.length() }
            visible = true
            val counter = AtomicLong(0L)
            TexturePackSync.uploadPending(TexturePackClient(context), container, cacheDir) { bytes ->
                done = counter.addAndGet(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "texture pack upload for $appId failed")
        }
        TexturePackUploadPrompt.clear()
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.texture_pack_upload_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.texture_pack_upload_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.texture_pack_progress_bytes, megabytes(done), megabytes(total)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dontAsk = !dontAsk },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                    Text(
                        text = stringResource(R.string.texture_pack_dont_do_for_game),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                TexturePackSyncWorker.enqueueUploadSync(context, appId)
                TexturePackUploadPrompt.clear()
            }) {
                Text(stringResource(R.string.texture_pack_continue_background))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                TexturePackUploadPrompt.clear()
                if (dontAsk) {
                    val appContext = context.applicationContext
                    thread(name = "texture-pack-skip") {
                        try {
                            TexturePackGate.setSkipped(appContext, appId, true)
                            TexturePackPaths.cacheDirForApp(appContext, appId)
                                ?.takeIf { it.isDirectory }
                                ?.let { dir -> TexturePackSync.sourceFiles(dir).forEach { it.delete() } }
                        } catch (e: Exception) {
                            Timber.w(e, "could not disable texture pack uploads for $appId")
                        }
                    }
                }
            }) {
                Text(stringResource(R.string.texture_pack_skip))
            }
        },
    )
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f", bytes.toDouble() / (1024.0 * 1024.0))
