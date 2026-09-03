package app.gamenative.ui.screen.xr.windows

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class WindowsVrDiagnostics(context: Context, private val capacity: Int = 512) {
    private val entries = ArrayDeque<String>(capacity)
    private val file = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "gamenativevr/launch.log",
    ).apply { parentFile?.mkdirs() }

    @Synchronized
    fun begin(appId: String, executable: String, arguments: String) {
        trimIfNeeded()
        file.appendText(
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
        file.appendText("$entry\n")
    }

    @Synchronized
    fun recordFileTail(label: String, source: File, maxChars: Int = 12_000) {
        val tail = runCatching {
            source.takeIf(File::isFile)?.readText()?.takeLast(maxChars)
        }.getOrNull()
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

    private fun trimIfNeeded() {
        if (!file.isFile || file.length() <= 512 * 1024L) return
        file.writeText("[older GameNativeVR diagnostics trimmed]\n${file.readText().takeLast(256 * 1024)}")
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
}
