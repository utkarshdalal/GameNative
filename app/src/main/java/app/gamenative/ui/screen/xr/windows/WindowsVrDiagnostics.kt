package app.gamenative.ui.screen.xr.windows

import android.content.Context
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import timber.log.Timber

class WindowsVrDiagnostics(context: Context, private val capacity: Int = 512) {
    private val entries = ArrayDeque<String>(capacity)
    private val file = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "gamenativevr/launch.log",
    ).apply { runCatching { parentFile?.mkdirs() } }

    @Synchronized
    fun begin(appId: String, executable: String, arguments: String) {
        trimIfNeeded()
        appendToFile(
            buildString {
                appendLine()
                appendLine("===== GameNativeVR immersive launch ${timestamp()} =====")
                appendLine("pid=${Process.myPid()} appId=$appId")
                appendLine("executable=${executable.ifBlank { "<unresolved>" }}")
                appendLine("arguments=${arguments.ifBlank { "<none>" }}")
            },
        )
    }

    @Synchronized
    fun record(stage: String, value: String) {
        if (entries.size == capacity) entries.removeFirst()
        val entry = "${timestamp()} $stage $value"
        entries.addLast(entry)
        trimIfNeeded()
        appendToFile("$entry\n")
    }

    @Synchronized
    fun recordFileTail(label: String, source: File, maxChars: Int = 12_000) {
        val tail = runCatching { readTail(source, maxChars) }.getOrNull()
        record(
            "diagnostics",
            if (tail.isNullOrBlank()) {
                "$label is unavailable at ${source.absolutePath}"
            } else {
                "$label tail (${source.absolutePath}):\n$tail"
            },
        )
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun compactStatus(): String = entries.lastOrNull() ?: "idle"

    fun logFile(): File = file

    private fun readTail(source: File, maxChars: Int): String? {
        if (!source.isFile) return null
        return RandomAccessFile(source, "r").use { input ->
            val length = input.length()
            val bytes = ByteArray(minOf(length, maxChars.toLong() * 4).toInt())
            input.seek(length - bytes.size)
            input.readFully(bytes)
            bytes.toString(Charsets.UTF_8).takeLast(maxChars)
        }
    }

    private fun appendToFile(text: String) {
        runCatching { file.appendText(text) }.onFailure { Timber.w(it, "Windows VR diagnostics write failed") }
    }

    private fun trimIfNeeded() {
        runCatching {
            if (!file.isFile || file.length() <= 512 * 1024L) return
            file.writeText("[older GameNativeVR diagnostics trimmed]\n${readTail(file, 256 * 1024).orEmpty()}")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
}
