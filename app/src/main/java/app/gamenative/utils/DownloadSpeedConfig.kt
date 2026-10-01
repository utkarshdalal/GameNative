package app.gamenative.utils

import app.gamenative.PrefManager

class DownloadSpeedConfig {
    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors()

    /**
     * Process-pool size (inflate/decrypt/hash/write workers) — the ONLY core-scaled knob.
     * Ratios borrowed from Bannerlator's DownloadSpeedConfig.
     */
    val maxDecompress: Int
        get() = when (PrefManager.downloadSpeed) {
            8 -> (cpuCores * 0.2).toInt().coerceAtLeast(1)
            16 -> (cpuCores * 0.4).toInt().coerceAtLeast(1)
            24 -> (cpuCores * 0.5).toInt().coerceAtLeast(1)
            32 -> (cpuCores * 0.8).toInt().coerceAtLeast(1)
            else -> 1
        }

    /**
     * Network parallelism = the max number of concurrent in-flight requests the tier permits.
     * In the native (Rust) engines this is the adaptive-window CEILING: the engine bootstraps at
     * 8 and ramps toward it ONLY while measured throughput keeps rising and errors/timeouts stay
     * low, clamped to distinct-CDN-hosts x per-host-cap (8); a weak/thin connection settles far
     * below it and is never flooded. The legacy Kotlin fallback pipelines treat it as a plain
     * concurrent-request count. Async requests are cheap, so this per-tier knob is independent
     * of cores — only the process pool ([maxDecompress]) derives from cores (tier values
     * borrowed from Bannerlator's DownloadSpeedConfig.maxNetworkWindow):
     *
     *   8 (slow) = 6    16 (medium) = 16    24 (fast) = 32    32 (blazing) = 128
     *
     * The engine still hard-bounds in-flight bytes with its byte budget (24-256 MiB of
     * reservations, scaling with the window), so a high tier costs concurrency, not
     * unbounded heap. 128 pairs with the engine's per-host cap of 8: a 16-host Steam CDN
     * pool can actually fill it. Kept at 128 (not 96) on device evidence: the best measured
     * run (33 MB/s) parked at ~50 UNDER this ceiling — the ceiling was never the problem,
     * over-parking was, and the engine's latency-congestion shrink now handles that.
     */
    val maxDownloads: Int
        get() = when (PrefManager.downloadSpeed) {
            8 -> 6
            16 -> 16
            24 -> 32
            32 -> 128
            else -> 6
        }
}
