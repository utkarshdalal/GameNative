package app.gamenative.utils

import app.gamenative.PrefManager

class DownloadSpeedConfig {
    private data class Limits(
        val maxDownloads: Int,
        val maxDecompress: Int
    )

    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors()

    private val limits: Limits
        get() = when (PrefManager.downloadSpeed) {
            8 -> Limits(
                maxDownloads = (cpuCores * 0.75).toInt().coerceIn(3, 8),
                maxDecompress = (cpuCores * 0.25).toInt().coerceIn(1, 3)
            )
            16 -> Limits(
                maxDownloads = (cpuCores * 1.0).toInt().coerceIn(4, 12),
                maxDecompress = (cpuCores * 0.33).toInt().coerceIn(2, 4)
            )
            24 -> Limits(
                maxDownloads = (cpuCores * 1.25).toInt().coerceIn(6, 16),
                maxDecompress = (cpuCores * 0.4).toInt().coerceIn(2, 5)
            )
            32 -> Limits(
                maxDownloads = (cpuCores * 1.5).toInt().coerceIn(8, 20),
                maxDecompress = (cpuCores * 0.5).toInt().coerceIn(3, 6)
            )
            else -> Limits(
                maxDownloads = 3,
                maxDecompress = 1
            )
        }

    val maxDownloads: Int
        get() = limits.maxDownloads

    val maxDecompress: Int
        get() = limits.maxDecompress
}
