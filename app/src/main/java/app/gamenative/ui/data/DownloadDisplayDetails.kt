package app.gamenative.ui.data

/**
 * Bundles the download/install flags passed to AppScreenContent. Grouping them keeps the composable's
 * parameter count low enough to avoid the ART verifier rejecting the generated method (VerifyError).
 */
data class DownloadDisplayDetails(
    val isInstalled: Boolean,
    val isValidToDownload: Boolean,
    val isDownloading: Boolean,
    val downloadProgress: Float,
    val hasPartialDownload: Boolean,
    val isUpdatePending: Boolean,
    val hasLeftoverInstall: Boolean = false,
)
