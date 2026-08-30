package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import com.winlator.core.GPUInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream

object DebugReportUtils {

    private const val HEADER_FILE = "header.json"
    private const val LOG_FILE = "log.gz"
    private const val LOG_HEAD_BYTES = 1L * 1024 * 1024
    private const val LOG_TAIL_BYTES = 7L * 1024 * 1024
    private const val LOG_MAX_BYTES = LOG_HEAD_BYTES + LOG_TAIL_BYTES

    fun reportsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "debug_reports")

    fun wineLogFile(context: Context, appId: String): File =
        File(context.getExternalFilesDir(null), "wine_logs/debug_run_$appId.log")

    fun headerFile(reportDir: File): File = File(reportDir, HEADER_FILE)

    fun logFile(reportDir: File): File = File(reportDir, LOG_FILE)

    fun readHeader(reportDir: File): JSONObject? = try {
        val file = headerFile(reportDir)
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        Timber.e(e, "DebugReportUtils: Failed to read header from $reportDir")
        null
    }

    fun writeIssueText(reportDir: File, issueText: String): Boolean {
        return try {
            val header = readHeader(reportDir) ?: return false
            header.put("issueText", issueText)
            headerFile(reportDir).writeText(header.toString())
            true
        } catch (e: Exception) {
            Timber.e(e, "DebugReportUtils: Failed to write issue text to $reportDir")
            false
        }
    }

    fun deleteReport(reportDir: File) {
        reportDir.deleteRecursively()
    }

    suspend fun createPendingReport(context: Context, appId: String): File? = withContext(Dispatchers.IO) {
        var reportDir: File? = null
        try {
            val wineLog = wineLogFile(context, appId)
            if (!wineLog.exists() || wineLog.length() == 0L) {
                Timber.w("DebugReportUtils: No debug run log found for $appId")
                return@withContext null
            }

            awaitStableSize(wineLog)

            val dir = File(reportsDir(context), "${appId}_${System.currentTimeMillis()}")
            reportDir = dir
            dir.mkdirs()

            val claimedLog = File(dir, "raw.log")
            if (!wineLog.renameTo(claimedLog)) {
                val sizeBefore = wineLog.length()
                wineLog.copyTo(claimedLog, overwrite = true)
                if (wineLog.length() == sizeBefore) {
                    wineLog.delete()
                }
            }

            compressLog(claimedLog, logFile(dir))
            headerFile(dir).writeText(buildHeader(context, appId).toString())
            claimedLog.delete()

            dir
        } catch (e: Exception) {
            Timber.e(e, "DebugReportUtils: Failed to create pending report for $appId")
            reportDir?.deleteRecursively()
            null
        }
    }

    private suspend fun awaitStableSize(file: File) {
        val start = System.currentTimeMillis()
        var lastSize = file.length()
        var lastChange = start
        while (System.currentTimeMillis() - start < 3_000) {
            delay(100)
            val size = file.length()
            if (size != lastSize) {
                lastSize = size
                lastChange = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChange >= 500) {
                return
            }
        }
    }

    private fun buildHeader(context: Context, appId: String): JSONObject {
        val container = ContainerUtils.getContainer(context, appId)

        val gpu = try {
            GPUInformation.getRenderer(context)
        } catch (e: Exception) {
            Timber.e(e, "DebugReportUtils: Failed to get GPU info")
            "Unknown GPU"
        }

        val avgFps = container.getSessionMetadata("avg_fps", "").toFloatOrNull()
        val sessionLengthSec = container.getSessionMetadata("session_length_sec", "").toIntOrNull()

        return JSONObject().apply {
            put("gameName", ContainerUtils.resolveGameName(appId))
            put("appId", appId)
            put("deviceName", HardwareUtils.getMachineName())
            put("deviceModel", Build.MODEL)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("socName", HardwareUtils.getSOCName() ?: JSONObject.NULL)
            put("gpuName", gpu)
            put("androidVersion", Build.VERSION.RELEASE)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("configs", JSONObject(container.containerJson))
            if (avgFps != null) put("avgFps", avgFps.toDouble()) else put("avgFps", JSONObject.NULL)
            if (sessionLengthSec != null) put("sessionLengthSec", sessionLengthSec) else put("sessionLengthSec", JSONObject.NULL)
        }
    }

    private fun compressLog(source: File, destination: File) {
        GZIPOutputStream(destination.outputStream().buffered()).use { out ->
            val length = source.length()
            if (length <= LOG_MAX_BYTES) {
                source.inputStream().buffered().use { it.copyTo(out) }
            } else {
                RandomAccessFile(source, "r").use { raf ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = LOG_HEAD_BYTES
                    while (remaining > 0) {
                        val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    out.write("\n[... ${length - LOG_MAX_BYTES} bytes truncated ...]\n".toByteArray())
                    raf.seek(length - LOG_TAIL_BYTES)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}
