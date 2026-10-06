package app.gamenative.ui.screen.support

import android.content.Context
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DebugRunParams
import java.io.File
import java.time.Instant
import org.json.JSONObject
import timber.log.Timber

object SupportAppliedRun {

    private const val DEFAULT_MIN_SECONDS = 60
    private const val MIN_FRAMES = 60L
    private const val MAX_ATTEMPTS = 3

    data class Pending(val run: DebugRunParams, val issueText: String)

    private fun records(context: Context, appId: String): List<File> {
        if (appId.isEmpty() || !ContainerUtils.hasContainer(context, appId)) return emptyList()
        val container = ContainerUtils.getContainer(context, appId)
        return listOf(
            SupportPatchApplier.appliedRecordFile(container),
            SupportComponentApplier.appliedRecordFile(container),
        )
    }

    private fun read(file: File): JSONObject? =
        runCatching { if (file.isFile) JSONObject(file.readText()) else null }.getOrNull()

    private fun text(record: JSONObject, key: String): String =
        (record.opt(key) as? String).orEmpty()

    private fun issueText(record: JSONObject): String {
        val patchsetId = text(record, "patchsetId")
        if (patchsetId.isNotEmpty()) return "Result of the applied patch ${patchsetId.take(8)}"
        val version = text(record, "version").ifEmpty { text(record, "to") }
        return "Result of the applied ${text(record, "type")} $version".trim()
    }

    fun pending(context: Context, appId: String): Pending? = try {
        records(context, appId)
            .mapNotNull { read(it) }
            .filter { !it.has("reportedAt") && !it.optBoolean("restored", false) }
            .maxByOrNull { text(it, "appliedAt") }
            ?.let { record ->
                val run = DebugRunParams.fromJson(record.optJSONObject("run")) ?: DebugRunParams(minSeconds = DEFAULT_MIN_SECONDS)
                Pending(run, issueText(record))
            }
    } catch (e: Exception) {
        Timber.w(e, "Reading the applied support records failed")
        null
    }

    private fun headerNumber(header: JSONObject?, key: String): Long? =
        header?.takeIf { it.has(key) && !it.isNull(key) }?.optLong(key, -1L)?.takeIf { it >= 0 }

    fun recordReportedRun(context: Context, appId: String, header: JSONObject?) {
        try {
            val now = Instant.now().toString()
            val frames = headerNumber(header, "totalFrames")
            val seconds = headerNumber(header, "sessionLengthSec")
            records(context, appId).forEach { file ->
                val record = read(file) ?: return@forEach
                if (record.has("reportedAt")) return@forEach
                val minSeconds = DebugRunParams.fromJson(record.optJSONObject("run"))?.minSeconds ?: DEFAULT_MIN_SECONDS
                val realSession = (frames != null && frames >= MIN_FRAMES) || (seconds != null && seconds >= minSeconds)
                val attempts = record.optInt("attempts", 0) + 1
                record.put("attempts", attempts)
                if (realSession || attempts >= MAX_ATTEMPTS) record.put("reportedAt", now)
                file.writeText(record.toString())
            }
        } catch (e: Exception) {
            Timber.w(e, "Recording the reported run on the applied support records failed")
        }
    }

    fun markReported(context: Context, appId: String) {
        try {
            val now = Instant.now().toString()
            records(context, appId).forEach { file ->
                val record = read(file) ?: return@forEach
                if (record.has("reportedAt")) return@forEach
                record.put("reportedAt", now)
                file.writeText(record.toString())
            }
        } catch (e: Exception) {
            Timber.w(e, "Marking the applied support records reported failed")
        }
    }
}
