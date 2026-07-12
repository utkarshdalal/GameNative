package app.gamenative.mods

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ModDownloadInfo(
    val installId: String,
    val appId: String,
    val displayName: String,
    val progress: Float = 0f,
    val status: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

object ModDownloadRegistry {
    private val downloads = MutableStateFlow<Map<String, ModDownloadInfo>>(emptyMap())
    private val canceledImports = mutableSetOf<String>()

    fun observeDownloads(): StateFlow<Map<String, ModDownloadInfo>> = downloads.asStateFlow()

    fun get(installId: String): ModDownloadInfo? = downloads.value[installId]

    fun start(installId: String, appId: String, displayName: String) {
        synchronized(canceledImports) {
            canceledImports.remove(installId)
        }
        val info = ModDownloadInfo(
            installId = installId,
            appId = appId,
            displayName = displayName,
            status = "Starting",
        )
        downloads.update { current -> current + (installId to info) }
    }

    fun update(
        installId: String,
        progress: Float,
        status: String,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
    ) {
        downloads.update { currentDownloads ->
            val current = currentDownloads[installId] ?: return@update currentDownloads
            val updated = current.copy(
                progress = progress.coerceIn(0f, 1f),
                status = status,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            )
            currentDownloads + (installId to updated)
        }
    }

    fun finish(installId: String) {
        downloads.update { current -> current - installId }
        synchronized(canceledImports) {
            canceledImports.remove(installId)
        }
    }

    fun requestCancel(installId: String) {
        synchronized(canceledImports) {
            canceledImports += installId
        }
    }

    fun isCancelRequested(installId: String): Boolean =
        synchronized(canceledImports) { installId in canceledImports }
}
