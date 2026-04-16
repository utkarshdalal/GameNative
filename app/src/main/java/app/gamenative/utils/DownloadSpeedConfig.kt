package app.gamenative.utils

import app.gamenative.PrefManager

class DownloadSpeedConfig {

    companion object {
        val downloadRatio = when (PrefManager.downloadSpeed) {
            8 -> {
                0.6
            }

            16 -> {
                1.2
            }

            24 -> {
                1.5
            }

            32 -> {
                2.4
            }

            else -> {
                0.6
            }
        }

        val decompressRatio = when (PrefManager.downloadSpeed) {
            8 -> {
                0.2
            }

            16 -> {
                0.4
            }

            24 -> {
                0.5
            }

            32 -> {
                0.8
            }

            else -> {
                0.2
            }
        }

        val cpuCores = Runtime.getRuntime().availableProcessors()
        val maxDownloads = (cpuCores * downloadRatio).toInt().coerceAtLeast(1)
        val maxDecompress = (cpuCores * decompressRatio).toInt().coerceAtLeast(1)
    }
}
