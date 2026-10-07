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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.NetworkMonitor
import app.gamenative.R
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import app.gamenative.api.SupportFilesRequest
import app.gamenative.ui.theme.PluviaTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

private const val WIFI_WARNING_BYTES = 50L * 1024 * 1024

private data class LocalFile(val item: SupportFilesRequest.Item, val file: File?, val size: Long?) {
    val tooLarge: Boolean get() = !item.list && size != null && size > item.maxBytes
    val uploadable: Boolean get() = !item.list && file != null && size != null && !tooLarge
    val listable: Boolean get() = item.list && file != null
}

private sealed class UploadState {
    data class Hashing(val progress: Float) : UploadState()
    data class Uploading(val progress: Float) : UploadState()
    data object Uploaded : UploadState()
    data object Listing : UploadState()
    data class Listed(val entries: Int) : UploadState()
    data class Failed(val message: String) : UploadState()
}

private sealed class FilesStatus {
    data object Done : FilesStatus()
    data object Cancelled : FilesStatus()
}

private fun describe(result: ApiResult<*>): String = when (result) {
    is ApiResult.Success -> ""
    is ApiResult.HttpError -> "HTTP ${result.code} ${result.message}".trim()
    is ApiResult.NetworkError -> result.exception.javaClass.simpleName
}

private suspend fun findFiles(context: android.content.Context, appId: String, request: SupportFilesRequest): List<LocalFile>? =
    withContext(Dispatchers.IO) {
        try {
            val roots = SupportGameFiles.roots(context, appId) ?: return@withContext null
            request.files.map { item ->
                if (item.list) {
                    LocalFile(item, SupportGameFiles.resolveDir(roots, item.path), null)
                } else {
                    val file = SupportGameFiles.resolve(roots, item.path)?.takeIf { it.isFile }
                    LocalFile(item, file, file?.length())
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Reading files for a support file request failed")
            null
        }
    }

private suspend fun uploadOne(
    conversationId: String,
    requestId: String,
    local: LocalFile,
    onState: (UploadState) -> Unit,
): UploadState {
    val file = local.file ?: return UploadState.Failed("")
    val job: Job = currentCoroutineContext().job
    return try {
        onState(UploadState.Hashing(0f))
        val size = file.length()
        val hash = withContext(Dispatchers.IO) {
            SupportGameFiles.sha256(file, job) { onState(UploadState.Hashing(it)) }
        }
        val slot = when (val begin = SupportApi.filesBegin(conversationId, requestId, local.item.path, size, hash)) {
            is ApiResult.Success -> begin.data
            else -> return UploadState.Failed(describe(begin))
        }
        onState(UploadState.Uploading(0f))
        val put = SupportApi.uploadToSignedUrl(slot.putUrl, file, size, hash) { onState(UploadState.Uploading(it)) }
        if (put !is ApiResult.Success) return UploadState.Failed(describe(put))
        val done = SupportApi.filesDone(conversationId, slot.fileId)
        if (done !is ApiResult.Success) return UploadState.Failed(describe(done))
        UploadState.Uploaded
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Timber.w(e, "Uploading a requested support file failed")
        UploadState.Failed(e.message ?: e.javaClass.simpleName)
    }
}

private suspend fun sendListing(conversationId: String, requestId: String, local: LocalFile): UploadState {
    val dir = local.file ?: return UploadState.Failed("")
    val job: Job = currentCoroutineContext().job
    return try {
        val listing = withContext(Dispatchers.IO) { SupportGameFiles.list(dir, local.item.depth, job) }
        val entries = JSONArray()
        listing.entries.forEach {
            entries.put(JSONObject().put("name", it.name).put("size", it.size).put("dir", it.dir).put("mtime", it.mtime))
        }
        when (val sent = SupportApi.filesListing(conversationId, requestId, local.item.path, entries, listing.truncated)) {
            is ApiResult.Success -> UploadState.Listed(listing.entries.size)
            else -> UploadState.Failed(describe(sent))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Timber.w(e, "Sending a requested support folder listing failed")
        UploadState.Failed(e.message ?: e.javaClass.simpleName)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilesRequestCard(
    message: SupportApi.Message,
    request: SupportFilesRequest,
    appId: String?,
    conversationId: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember(message.id) { mutableStateOf<List<LocalFile>?>(null) }
    var loaded by remember(message.id) { mutableStateOf(false) }
    val states = remember(message.id) { mutableStateMapOf<Int, UploadState>() }
    var uploadJob by remember(message.id) { mutableStateOf<Job?>(null) }
    var status by remember(message.id) { mutableStateOf<FilesStatus?>(null) }
    val online by NetworkMonitor.hasInternet.collectAsState()
    val wifi by NetworkMonitor.hasWifiOrEthernet.collectAsState()

    LaunchedEffect(message.id, appId) {
        files = if (appId == null) null else findFiles(context, appId, request)
        loaded = true
    }

    val busy = uploadJob != null
    val uploadable = files.orEmpty().withIndex().filter { it.value.uploadable && states[it.index] != UploadState.Uploaded }
    val totalBytes = uploadable.sumOf { it.value.size ?: 0L }
    val canUpload = request.applicable && files != null && uploadable.isNotEmpty() && !busy
    val hasFiles = request.files.any { !it.list }
    val listable = files.orEmpty().withIndex().filter { it.value.listable && states[it.index] !is UploadState.Listed }
    val canList = request.applicable && files != null && listable.isNotEmpty() && !busy

    fun track(job: Job) {
        uploadJob = job
        job.invokeOnCompletion { cause ->
            uploadJob = null
            if (cause is CancellationException) {
                states.keys.toList().forEach { key ->
                    val state = states[key]
                    if (state !is UploadState.Uploaded && state !is UploadState.Listed && state !is UploadState.Failed) states.remove(key)
                }
                status = FilesStatus.Cancelled
            }
        }
    }

    fun sendListings() {
        status = null
        track(
            scope.launch {
                for ((index, local) in listable) {
                    states[index] = UploadState.Listing
                    states[index] = sendListing(conversationId, request.requestId, local)
                }
            },
        )
    }

    fun upload() {
        status = null
        val job = scope.launch {
            var allDone = true
            for ((index, local) in uploadable) {
                val result = uploadOne(conversationId, request.requestId, local) { states[index] = it }
                states[index] = result
                if (result != UploadState.Uploaded) allDone = false
            }
            if (allDone) status = FilesStatus.Done
        }
        track(job)
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
                    imageVector = Icons.Filled.UploadFile,
                    contentDescription = null,
                    tint = PluviaTheme.colors.accentPurple,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.support_files_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            request.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!request.applicable) {
                Text(
                    text = stringResource(R.string.support_files_not_applicable),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else if (loaded && files == null) {
                Text(
                    text = stringResource(R.string.support_files_no_game),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            request.files.forEachIndexed { index, item ->
                val local = files?.getOrNull(index)
                val size = local?.size
                val label = when {
                    item.list -> if (local != null && local.file == null) {
                        stringResource(R.string.support_files_listing_missing, item.path)
                    } else {
                        stringResource(R.string.support_files_listing, item.path)
                    }
                    local == null -> item.path
                    size == null -> stringResource(R.string.support_files_missing, item.path)
                    local.tooLarge -> stringResource(R.string.support_files_too_large, item.path)
                    else -> stringResource(R.string.support_files_size, item.path, Formatter.formatShortFileSize(context, size))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (local != null && !local.uploadable && !local.listable) PluviaTheme.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
                item.why?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                    )
                }
                when (val state = states[index]) {
                    is UploadState.Hashing -> {
                        Text(
                            text = stringResource(R.string.support_files_hashing, item.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.textMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    is UploadState.Uploading -> {
                        Text(
                            text = stringResource(R.string.support_files_uploading, item.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.textMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    UploadState.Uploaded -> Text(
                        text = stringResource(R.string.support_files_uploaded, item.path),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.accentSuccess,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    UploadState.Listing -> {
                        Text(
                            text = stringResource(R.string.support_files_listing_sending, item.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.textMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    is UploadState.Listed -> Text(
                        text = stringResource(R.string.support_files_listing_sent, state.entries),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.accentSuccess,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is UploadState.Failed -> Text(
                        text = stringResource(
                            if (item.list) R.string.support_files_listing_failed else R.string.support_files_failed,
                            item.path,
                            state.message,
                        ).trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.accentDanger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    null -> Unit
                }
            }
            if (canUpload && totalBytes > WIFI_WARNING_BYTES && online && !wifi) {
                Text(
                    text = stringResource(R.string.support_files_wifi_warning, Formatter.formatShortFileSize(context, totalBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.accentWarning,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            status?.let { current ->
                Text(
                    text = stringResource(
                        if (current == FilesStatus.Done) R.string.support_files_done else R.string.support_files_cancelled,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current == FilesStatus.Done) PluviaTheme.colors.accentSuccess else PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) {
                    OutlinedFocusButton(
                        text = stringResource(R.string.cancel),
                        enabled = true,
                        onClick = { uploadJob?.cancel() },
                    )
                } else {
                    if (listable.isNotEmpty()) {
                        FocusableButton(
                            text = stringResource(R.string.support_files_send_listing),
                            onClick = { sendListings() },
                            enabled = canList && appId != null,
                        )
                    }
                    if (hasFiles && status != FilesStatus.Done) {
                        FocusableButton(
                            text = stringResource(R.string.support_files_upload),
                            onClick = { upload() },
                            enabled = canUpload && appId != null,
                        )
                    }
                }
            }
        }
    }
}
