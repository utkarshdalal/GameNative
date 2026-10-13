package app.gamenative.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Keeps Android's own record of why this process last died. A game that takes the whole app
 * down (low-memory kill, native crash in the X server or renderer, ANR) leaves no wine log
 * tail and no report, so the next report carries these records instead.
 */
object AppExitRecorder {

    private const val DIR = "exit_info"
    private const val EXITS_FILE = "exits.json"
    private const val LAST_SEEN_FILE = "last_seen"
    private const val MAX_RECORDS = 10
    private const val TRACE_HEAD_BYTES = 64 * 1024
    private const val TRACE_TAIL_BYTES = 192 * 1024

    private val HARD_REASONS = setOf(
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_UNKNOWN,
    )

    private val IGNORED_REASONS = setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
    )

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    private fun exitsFile(context: Context): File = File(dir(context), EXITS_FILE)

    private fun traceFile(context: Context, timestamp: Long): File = File(dir(context), "$timestamp.trace.txt")

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    /** Reads the exit records Android kept since the last call and stores the new ones. Call once per process start. */
    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val lastSeenFile = File(dir(context), LAST_SEEN_FILE)
            val lastSeen = lastSeenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val records = am.getHistoricalProcessExitReasons(null, 0, MAX_RECORDS)
            val fresh = records
                .filter { it.timestamp > lastSeen && it.processName == context.packageName && it.reason !in IGNORED_REASONS }
                .sortedBy { it.timestamp }
            if (fresh.isEmpty()) {
                records.maxOfOrNull { it.timestamp }?.let { newest -> if (newest > lastSeen) writeLastSeen(lastSeenFile, newest) }
                return
            }
            dir(context).mkdirs()
            val stored = JSONArray()
            readAll(context).forEach { stored.put(it) }
            for (info in fresh) {
                val entry = JSONObject().apply {
                    put("timestamp", info.timestamp)
                    put("reason", reasonName(info.reason))
                    put("reasonCode", info.reason)
                    put("status", info.status)
                    put("importance", info.importance)
                    put("pssKb", info.pss)
                    put("rssKb", info.rss)
                    put("description", info.description ?: JSONObject.NULL)
                    put("hard", info.reason in HARD_REASONS)
                }
                val trace = readTrace(info)
                if (trace != null) {
                    val file = traceFile(context, info.timestamp)
                    file.writeText(trace)
                    entry.put("trace", file.name)
                }
                stored.put(entry)
                Timber.w("AppExitRecorder: previous process ended: %s %s", entry.optString("reason"), entry.optString("description"))
            }
            val kept = JSONArray()
            val start = (stored.length() - MAX_RECORDS).coerceAtLeast(0)
            for (i in start until stored.length()) kept.put(stored.getJSONObject(i))
            exitsFile(context).writeText(kept.toString())
            pruneTraces(context, kept)
            writeLastSeen(lastSeenFile, fresh.last().timestamp)
        } catch (e: Exception) {
            Timber.w(e, "AppExitRecorder: failed to record exit reasons")
        }
    }

    /** Stored records newer than [sinceMs], newest first, at most [limit]. */
    fun recentExits(context: Context, sinceMs: Long, limit: Int = 5): List<JSONObject> =
        readAll(context)
            .filter { it.optLong("timestamp") > sinceMs }
            .sortedByDescending { it.optLong("timestamp") }
            .take(limit)

    fun newestHardExit(context: Context, sinceMs: Long): JSONObject? =
        recentExits(context, sinceMs, MAX_RECORDS).firstOrNull { it.optBoolean("hard") }

    fun traceFor(context: Context, exit: JSONObject): File? =
        exit.optString("trace").takeIf { it.isNotEmpty() }?.let { File(dir(context), it) }?.takeIf { it.exists() }

    private fun readAll(context: Context): List<JSONObject> = try {
        val file = exitsFile(context)
        if (!file.exists()) emptyList()
        else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
    } catch (e: Exception) {
        Timber.w(e, "AppExitRecorder: failed to read stored exits")
        emptyList()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): String? {
        if (info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE && info.reason != ApplicationExitInfo.REASON_ANR) return null
        return try {
            info.traceInputStream?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size <= TRACE_HEAD_BYTES + TRACE_TAIL_BYTES) {
                    String(bytes)
                } else {
                    String(bytes, 0, TRACE_HEAD_BYTES) +
                        "\n\n[... ${bytes.size - TRACE_HEAD_BYTES - TRACE_TAIL_BYTES} bytes skipped ...]\n\n" +
                        String(bytes, bytes.size - TRACE_TAIL_BYTES, TRACE_TAIL_BYTES)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "AppExitRecorder: failed to read exit trace")
            null
        }
    }

    private fun pruneTraces(context: Context, kept: JSONArray) {
        val live = (0 until kept.length()).mapNotNull { kept.optJSONObject(it)?.optString("trace")?.takeIf { it.isNotEmpty() } }.toSet()
        dir(context).listFiles()?.filter { it.name.endsWith(".trace.txt") && it.name !in live }?.forEach { it.delete() }
    }

    private fun writeLastSeen(file: File, timestamp: Long) {
        file.parentFile?.mkdirs()
        file.writeText(timestamp.toString())
    }
}
