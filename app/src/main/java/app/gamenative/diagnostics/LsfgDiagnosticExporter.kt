package app.gamenative.diagnostics

import android.content.Context
import android.os.Build
import app.gamenative.CrashHandler
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the single-file, best-effort LSFG diagnostic report exported from Debug settings.
 *
 * This exporter is intentionally independent from Adaptive Frame Generation lifecycle state. It
 * discovers the fixed-mode runtime artifacts at export time so diagnostics remain available for
 * multiplier 2x/3x/4x and for the disabled-path baseline.
 */
object LsfgDiagnosticExporter {
    private const val TEXT_TAIL_BYTES = 2L * 1024L * 1024L
    private const val NATIVE_EVENT_TAIL_BYTES = 4L * 1024L * 1024L
    private const val LOGCAT_LINES = 8_000
    private const val PRESENTATION_LOG_LINES = 4_000
    private const val MAX_SCAN_DEPTH = 14
    private const val MAX_SCAN_NODES = 25_000

    private val graphicsEnvironmentKeys = listOf(
        "VK_ICD_FILENAMES",
        "ADRENOTOOLS_DRIVER_NAME",
        "ADRENOTOOLS_DRIVER_PATH",
        "VK_LAYER_PATH",
        "VK_INSTANCE_LAYERS",
        "VK_LOADER_LAYERS_ENABLE",
        "VK_LOADER_DEBUG",
        "LSFG_CONFIG",
        "LSFG_PROCESS_EXE",
        "LD_LIBRARY_PATH",
    )

    private val presentationKeywords = listOf(
        "lsfg",
        "vulkan",
        "vkqueuepresentkhr",
        "present",
        "swapchain",
        "ahardwarebuffer",
        " ahb",
        "fence",
        "semaphore",
        "sync",
        "acquire",
        "generated",
        "framegen",
        "frame gen",
        "multiplier",
        "image",
        "black",
        "error",
        "warning",
        "failed",
    )

    fun defaultFileName(now: Date = Date()): String =
        "gamenative-lsfg-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)}.txt"

    fun buildReport(context: Context): String {
        val appContext = context.applicationContext
        val warnings = mutableListOf<String>()
        val report = StringBuilder(128 * 1024)
        val artifacts = discoverArtifacts(appContext, warnings)
        val appLogcat = runCatching { CrashHandler.getAppLogs(LOGCAT_LINES) }
            .getOrElse {
                warnings += "APP LOGCAT capture failed: ${safeMessage(it)}"
                ""
            }

        fun section(name: String, body: () -> String) {
            report.append("===== ").append(name).append(" =====\n")
            val text = try {
                body().trimEnd().ifBlank { "unavailable" }
            } catch (t: Throwable) {
                val message = safeMessage(t)
                warnings += "$name unavailable: $message"
                "unavailable: $message"
            }
            report.append(text).append("\n\n")
        }

        section("CAPTURE") {
            buildString {
                appendLine("captured_at=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())}")
                appendLine("mode=fixed-multiplier")
                appendLine("adaptive_frame_generation=disabled")
                appendLine("scan_roots=${artifacts.scanRoots.joinToString(";")}")
                appendLine("scanned_nodes=${artifacts.scannedNodes}")
            }
        }

        section("DEVICE") {
            buildString {
                appendLine("manufacturer=${Build.MANUFACTURER}")
                appendLine("brand=${Build.BRAND}")
                appendLine("model=${Build.MODEL}")
                appendLine("device=${Build.DEVICE}")
                appendLine("hardware=${Build.HARDWARE}")
                appendLine("board=${Build.BOARD}")
                appendLine("android_release=${Build.VERSION.RELEASE}")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
                if (Build.VERSION.SDK_INT >= 31) {
                    appendLine("soc_manufacturer=${Build.SOC_MANUFACTURER}")
                    appendLine("soc_model=${Build.SOC_MODEL}")
                }
            }
        }

        section("APP / RUNTIME") {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            buildString {
                appendLine("package=${appContext.packageName}")
                appendLine("version_name=${packageInfo.versionName}")
                val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                appendLine("version_code=$versionCode")
                appendLine("process_pid=${android.os.Process.myPid()}")
                appendLine("lsfg_runtime_marker=${readSmallFile(artifacts.runtimeMarker) ?: "unavailable"}")
                appendLine("lsfg_layer=${artifacts.layer?.absolutePath ?: "unavailable"}")
                appendLine("lsfg_layer_sha256=${sha256(artifacts.layer) ?: "unavailable"}")
                appendLine("lsfg_layer_bytes=${artifacts.layer?.takeIf(File::isFile)?.length() ?: 0L}")
                appendLine("selected_environment:")
                var environmentFound = false
                graphicsEnvironmentKeys.forEach { key ->
                    System.getenv(key)?.takeIf { it.isNotBlank() }?.let { value ->
                        environmentFound = true
                        appendLine("  $key=$value")
                    }
                }
                if (!environmentFound) appendLine("  unavailable")
            }
        }

        section("GPU / VULKAN CAPABILITIES") {
            val capabilityLines = appLogcat.lineSequence()
                .filter { line ->
                    line.contains("capability", ignoreCase = true) ||
                        line.contains("AHB", ignoreCase = true) ||
                        line.contains("AHardwareBuffer", ignoreCase = true) ||
                        line.contains("vulkan", ignoreCase = true) ||
                        line.contains("driver", ignoreCase = true) ||
                        line.contains("device", ignoreCase = true) && line.contains("LSFG", ignoreCase = true)
                }
                .takeLastLines(PRESENTATION_LOG_LINES)
            capabilityLines.ifBlank {
                warnings += "GPU / VULKAN CAPABILITIES: no matching lines remained in app logcat"
                "No matching GPU/Vulkan capability lines captured."
            }
        }

        section("LSFG CONFIGURATION") {
            labeledFileOrUnavailable(artifacts.config, TEXT_TAIL_BYTES, warnings, "conf.toml")
        }

        section("LSFG CURRENT STATE") {
            buildString {
                appendLine("--- stats.txt ---")
                appendLine(readTailOrUnavailable(artifacts.stats, TEXT_TAIL_BYTES, warnings, "stats.txt"))
                appendLine("--- vsync.txt ---")
                appendLine(readTailOrUnavailable(artifacts.vsync, TEXT_TAIL_BYTES, warnings, "vsync.txt"))
                appendLine("--- present-vsync.txt ---")
                appendLine(readTailOrUnavailable(artifacts.presentVsync, TEXT_TAIL_BYTES, warnings, "present-vsync.txt"))
            }
        }

        section("3X / 4X PRESENTATION LOGCAT") {
            val filtered = appLogcat.lineSequence()
                .filter { line ->
                    val normalized = line.lowercase(Locale.US)
                    presentationKeywords.any(normalized::contains)
                }
                .takeLastLines(PRESENTATION_LOG_LINES)
            filtered.ifBlank {
                warnings += "3X / 4X PRESENTATION LOGCAT: no matching lines remained in app logcat"
                "No LSFG/present/swapchain/synchronization lines captured."
            }
        }

        section("LSFG NATIVE EVENTS") {
            labeledFileOrUnavailable(
                artifacts.nativeDiagnostics,
                NATIVE_EVENT_TAIL_BYTES,
                warnings,
                "diagnostics.log",
            )
        }

        section("WRAPPER DIAGNOSTICS") {
            labeledFileOrUnavailable(
                artifacts.wrapperDiagnostics,
                TEXT_TAIL_BYTES,
                warnings,
                "wrapper_diag_*",
            )
        }

        section("PERFORMANCE TIMELINE") {
            labeledFileOrUnavailable(
                artifacts.performanceMetrics,
                NATIVE_EVENT_TAIL_BYTES,
                warnings,
                "performance_metrics_*.jsonl",
            )
        }

        section("APP LOGCAT") {
            if (appLogcat.isBlank()) "unavailable" else appLogcat
        }

        report.append("===== EXPORT WARNINGS =====\n")
        if (warnings.isEmpty()) {
            report.append("none\n")
        } else {
            warnings.distinct().forEach { report.append("- ").append(it).append('\n') }
        }
        report.append('\n')

        return report.toString()
    }

    private data class DiscoveredArtifacts(
        val scanRoots: List<String>,
        val scannedNodes: Int,
        val config: File?,
        val stats: File?,
        val vsync: File?,
        val presentVsync: File?,
        val nativeDiagnostics: File?,
        val wrapperDiagnostics: File?,
        val performanceMetrics: File?,
        val runtimeMarker: File?,
        val layer: File?,
    )

    private fun discoverArtifacts(context: Context, warnings: MutableList<String>): DiscoveredArtifacts {
        val roots = linkedMapOf<String, File>()
        fun addRoot(file: File?) {
            if (file == null || !file.exists()) return
            val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            roots[key] = file
        }

        addRoot(File(context.applicationInfo.dataDir))
        addRoot(context.getExternalFilesDir(null))
        addRoot(context.cacheDir)

        var scanned = 0
        val candidates = mutableListOf<File>()
        roots.values.forEach { root ->
            if (scanned >= MAX_SCAN_NODES) return@forEach
            runCatching {
                for (file in root.walkTopDown().maxDepth(MAX_SCAN_DEPTH)) {
                    if (scanned++ >= MAX_SCAN_NODES) break
                    if (!file.isFile) continue
                    val name = file.name
                    val path = file.absolutePath.replace('\\', '/').lowercase(Locale.US)
                    val isLsfgPath = path.contains("lsfg-vk") || path.contains("lsfg_vk")
                    if (
                        (isLsfgPath && name in setOf(
                            "conf.toml",
                            "stats.txt",
                            "vsync.txt",
                            "present-vsync.txt",
                            "diagnostics.log",
                            ".lsfg_vk_runtime_version",
                            "liblsfg-vk-layer.so",
                        )) ||
                        (name.startsWith("wrapper_diag_") && name.endsWith(".txt")) ||
                        (name.startsWith("performance_metrics_") && name.endsWith(".jsonl"))
                    ) {
                        candidates += file
                    }
                }
            }.onFailure {
                warnings += "Artifact scan failed under ${root.absolutePath}: ${safeMessage(it)}"
            }
        }
        if (scanned >= MAX_SCAN_NODES) {
            warnings += "Artifact scan reached node limit ($MAX_SCAN_NODES); newest matching files found so far were used"
        }

        fun newest(predicate: (File) -> Boolean): File? =
            candidates.asSequence().filter(predicate).maxByOrNull { it.lastModified() }

        fun lsfgNamed(name: String): File? = newest { file ->
            file.name == name && file.absolutePath.replace('\\', '/').lowercase(Locale.US).let { path ->
                path.contains("lsfg-vk") || path.contains("lsfg_vk")
            }
        }

        return DiscoveredArtifacts(
            scanRoots = roots.keys.toList(),
            scannedNodes = scanned.coerceAtMost(MAX_SCAN_NODES),
            config = lsfgNamed("conf.toml"),
            stats = lsfgNamed("stats.txt"),
            vsync = lsfgNamed("vsync.txt"),
            presentVsync = lsfgNamed("present-vsync.txt"),
            nativeDiagnostics = lsfgNamed("diagnostics.log"),
            wrapperDiagnostics = newest { it.name.startsWith("wrapper_diag_") && it.name.endsWith(".txt") },
            performanceMetrics = newest { it.name.startsWith("performance_metrics_") && it.name.endsWith(".jsonl") },
            runtimeMarker = lsfgNamed(".lsfg_vk_runtime_version"),
            layer = lsfgNamed("liblsfg-vk-layer.so"),
        )
    }

    private fun labeledFileOrUnavailable(
        file: File?,
        maxBytes: Long,
        warnings: MutableList<String>,
        label: String,
    ): String {
        if (file == null || !file.isFile) {
            warnings += "$label unavailable: matching artifact not found"
            return "unavailable: matching artifact not found"
        }
        return runCatching { labeledFile(file, maxBytes) }
            .getOrElse {
                warnings += "$label unavailable: ${safeMessage(it)}"
                "unavailable: ${safeMessage(it)}"
            }
    }

    private fun labeledFile(file: File, maxBytes: Long): String = buildString {
        appendLine("file=${file.absolutePath}")
        appendLine("bytes=${file.length()}")
        append(readTail(file, maxBytes))
    }

    private fun readTailOrUnavailable(
        file: File?,
        maxBytes: Long,
        warnings: MutableList<String>,
        label: String,
    ): String {
        if (file == null || !file.isFile) {
            warnings += "$label unavailable: matching artifact not found"
            return "unavailable: matching artifact not found"
        }
        return runCatching { readTail(file, maxBytes) }
            .getOrElse {
                warnings += "$label unavailable: ${safeMessage(it)}"
                "unavailable: ${safeMessage(it)}"
            }
    }

    private fun readTail(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText(Charsets.UTF_8)
        val start = (length - maxBytes).coerceAtLeast(0L)
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val count = (length - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bytes = ByteArray(count)
            raf.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            val firstNewline = text.indexOf('\n')
            val body = if (firstNewline >= 0) text.substring(firstNewline + 1) else text
            "... truncated; showing last $maxBytes bytes ...\n$body"
        }
    }

    private fun readSmallFile(file: File?): String? = runCatching {
        file?.takeIf { it.isFile && it.length() <= 64L * 1024L }?.readText()?.trim()
    }.getOrNull()

    private fun sha256(file: File?): String? = runCatching {
        if (file == null || !file.isFile) return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }.getOrNull()

    private fun Sequence<String>.takeLastLines(maxLines: Int): String {
        val lines = ArrayDeque<String>(maxLines)
        forEach { line ->
            if (lines.size == maxLines) lines.removeFirst()
            lines.addLast(line)
        }
        return lines.joinToString("\n")
    }

    private fun safeMessage(t: Throwable): String =
        t.message?.replace('\n', ' ')?.take(240) ?: t.javaClass.simpleName
}
