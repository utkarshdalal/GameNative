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
