package app.gamenative.ui.data

data class DownloadDisplayDetails (
    val isInstalled: Boolean,
    val isValidToDownload: Boolean,
    val isDownloading: Boolean,
    val hasPartialDownload: Boolean,
    val downloadProgress: Float,
    val isUpdatePending: Boolean
)
