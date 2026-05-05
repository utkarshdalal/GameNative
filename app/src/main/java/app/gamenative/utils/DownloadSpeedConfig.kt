package app.gamenative.utils

import app.gamenative.PrefManager

class DownloadSpeedConfig {
    private data class Ratios(val download: Double, val decompress: Double)

    private val ratios: Ratios
        get() = when (PrefManager.downloadSpeed) {
            8 -> {
                Ratios(download = 0.6, decompress = 0.2)
            }

            16 -> {
                Ratios(download = 1.2, decompress = 0.4)
            }

            24 -> {
                Ratios(download = 1.5, decompress = 0.5)
            }

            32 -> {
                Ratios(download = 2.4, decompress = 0.8)
            }

            else -> {
                Ratios(download = 0.6, decompress = 0.2)
            }
        }

    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors()

    val maxDownloads: Int
        get() = (cpuCores * ratios.download).toInt().coerceAtLeast(1)

    val maxDecompress: Int
        get() = (cpuCores * ratios.decompress).toInt().coerceAtLeast(1)
}
