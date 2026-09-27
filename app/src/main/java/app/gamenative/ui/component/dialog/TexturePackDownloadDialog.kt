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
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val CHECK_TIMEOUT_MS = 5_000L

private data class PendingDownload(val key: String, val size: Long)

private data class DownloadPlan(val container: Container, val present: Int, val downloads: List<PendingDownload>)

@Composable
fun TexturePackDownloadDialog(
    appId: String,
    onLaunch: () -> Unit,
) {
    val context = LocalContext.current
    var total by remember(appId) { mutableStateOf(0L) }
    var done by remember(appId) { mutableStateOf(0L) }
    var visible by remember(appId) { mutableStateOf(false) }
    var launched by remember(appId) { mutableStateOf(false) }
    var dontAsk by remember(appId) { mutableStateOf(false) }

    val launchOnce: () -> Unit = {
        if (!launched) {
            launched = true
            onLaunch()
        }
    }

    LaunchedEffect(appId) {
        val client = TexturePackClient(context)
        val downloadPlan = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            try {
                plan(context, appId, client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "texture pack download check failed for $appId")
                null
            }
        }
        val pending = downloadPlan?.downloads
        if (downloadPlan == null || pending.isNullOrEmpty()) {
            launchOnce()
            return@LaunchedEffect
        }
        total = pending.sumOf { it.size }
        visible = true
        val cacheDir = TexturePackPaths.cacheDir(downloadPlan.container)
        val counter = AtomicLong(0L)
        val sizes = pending.associate { it.key to it.size }
        val written = TexturePackSync.downloadEntries(client, cacheDir, pending.map { it.key }) { key, _ ->
            done = counter.addAndGet(sizes[key] ?: 0L)
        }
        runCatching { TexturePackSync.recordServerEntries(downloadPlan.container, downloadPlan.present + written) }
            .onFailure { Timber.w(it, "could not record texture pack entries for $appId") }
        launchOnce()
    }

    if (!visible || launched) return

    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.texture_pack_download_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.texture_pack_download_dialog_subtitle),
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
            TextButton(
                onClick = {
                    if (dontAsk) {
                        TexturePackGate.setSkipped(context, appId, true)
                    } else {
                        TexturePackSyncWorker.enqueueDownloadSync(context, appId)
                    }
                    launchOnce()
                },
            ) {
                Text(stringResource(R.string.texture_pack_play_now))
            }
        },
    )
}

private suspend fun plan(
    context: android.content.Context,
    appId: String,
    client: TexturePackClient,
): DownloadPlan? {
    if (!ContainerUtils.hasContainer(context, appId)) return null
    val container = ContainerUtils.getContainer(context, appId)
    val cacheDir = TexturePackPaths.ensureCacheDir(container)
    val fingerprint = TexturePackSync.ensureFingerprint(client, container)
    if (fingerprint.isBlank()) return null
    val pack = TexturePackSync.fetchPack(client, container, fingerprint)
    if (TexturePackGate.policyDisabled(pack.policy)) return null
    val sizes = pack.entries.associate { it.key to it.size }
    val downloads = withContext(Dispatchers.IO) {
        TexturePackSync.downloadKeys(cacheDir, pack.entries, pack.pendingKeys)
            .map { PendingDownload(it, sizes[it] ?: 0L) }
    }
    val ready = pack.entries.count { !it.pending && it.key !in pack.pendingKeys }
    val present = ready - downloads.size
    TexturePackSync.recordServerEntries(container, present)
    return DownloadPlan(container, present, downloads)
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f", bytes.toDouble() / (1024.0 * 1024.0))
