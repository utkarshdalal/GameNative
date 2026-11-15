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
    private const val SESSION_FILE = "active_session.json"
    private const val PENDING_FILE = "pending_last_exit.json"
    private const val MAX_RECORDS = 10
    private const val TRACE_HEAD_BYTES = 64 * 1024
    private const val TRACE_TAIL_BYTES = 192 * 1024
    private const val PROTO_MAX_BYTES = 8 * 1024 * 1024

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
        val session = consumeSessionMarker(context)
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
            writePendingExit(context, fresh.last(), session)
            pruneTraces(context, kept)
            writeLastSeen(lastSeenFile, fresh.last().timestamp)
        } catch (e: Exception) {
            Timber.w(e, "AppExitRecorder: failed to record exit reasons")
        }
    }

    fun markSessionStart(context: Context, gameName: String) {
        try {
            dir(context).mkdirs()
            File(dir(context), SESSION_FILE).writeText(
                JSONObject().put("game_name", gameName).put("started", System.currentTimeMillis()).toString(),
            )
        } catch (e: Exception) {
            Timber.w(e, "AppExitRecorder: failed to mark session start")
        }
    }

    fun markSessionEnd(context: Context) {
        runCatching { File(dir(context), SESSION_FILE).delete() }
    }

    fun takePendingExit(context: Context): Map<String, Any> {
        val file = File(dir(context), PENDING_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            buildMap {
                put("last_exit_info_reason", json.getString("reason"))
                put("last_exit_info_ts", json.getLong("timestamp"))
                json.optString("game_name").takeIf { it.isNotEmpty() }?.let { put("last_exit_info_game", it) }
            }
        } catch (e: Exception) {
            Timber.w(e, "AppExitRecorder: failed to read pending exit")
            emptyMap()
        } finally {
            file.delete()
        }
    }

    private fun consumeSessionMarker(context: Context): JSONObject? {
        val file = File(dir(context), SESSION_FILE)
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun writePendingExit(context: Context, info: ApplicationExitInfo, session: JSONObject?) {
        try {
            val json = JSONObject().put("reason", reasonName(info.reason)).put("timestamp", info.timestamp)
            session?.takeIf { it.optLong("started") in 1 until info.timestamp }?.optString("game_name")
                ?.takeIf { it.isNotEmpty() }?.let { json.put("game_name", it) }
            File(dir(context), PENDING_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Timber.w(e, "AppExitRecorder: failed to keep pending exit")
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
                // Android 12+ hands out a native-crash tombstone as a protobuf, not text
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val bytes = readBounded(stream, PROTO_MAX_BYTES) ?: return "[tombstone larger than $PROTO_MAX_BYTES bytes, not kept]"
                    ProtoDump.dump(bytes, TRACE_HEAD_BYTES + TRACE_TAIL_BYTES)
                } else {
                    readHeadTail(stream)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "AppExitRecorder: failed to read exit trace")
            null
        }
    }

    private fun readBounded(stream: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Keeps the first [TRACE_HEAD_BYTES] and the last [TRACE_TAIL_BYTES] of a stream of any size. */
    private fun readHeadTail(stream: java.io.InputStream): String {
        val head = java.io.ByteArrayOutputStream()
        val tail = ByteArray(TRACE_TAIL_BYTES)
        var tailLen = 0
        var tailPos = 0
        var total = 0L
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            var off = 0
            if (head.size() < TRACE_HEAD_BYTES) {
                val take = minOf(n, TRACE_HEAD_BYTES - head.size())
                head.write(buf, 0, take)
                off = take
            }
            for (i in off until n) {
                tail[tailPos] = buf[i]
                tailPos = (tailPos + 1) % tail.size
                if (tailLen < tail.size) tailLen++
            }
        }
        val headText = String(head.toByteArray())
        if (total <= TRACE_HEAD_BYTES) return headText
        val skipped = total - TRACE_HEAD_BYTES - tailLen
        val ordered = ByteArray(tailLen)
        val startIdx = if (tailLen < tail.size) 0 else tailPos
        for (i in 0 until tailLen) ordered[i] = tail[(startIdx + i) % tail.size]
        val tailText = String(ordered)
        return if (skipped > 0) "$headText\n\n[... $skipped bytes skipped ...]\n\n$tailText" else headText + tailText
    }

    /**
     * Schema-free protobuf dump: field numbers with their values, nested messages indented. Enough for the
     * tombstone's signal, abort message, causes and backtraces to read as text without the Tombstone schema.
     */
    private object ProtoDump {
        fun dump(bytes: ByteArray, limit: Int): String {
            val sb = StringBuilder()
            sb.append("[tombstone protobuf, ").append(bytes.size).append(" bytes; fields by number, strings as text]\n")
            message(bytes, 0, bytes.size, 0, sb, limit)
            if (sb.length > limit) {
                sb.setLength(limit)
                sb.append("\n[... truncated ...]\n")
            }
            return sb.toString()
        }

        private fun message(b: ByteArray, from: Int, to: Int, depth: Int, sb: StringBuilder, limit: Int): Boolean {
            var i = from
            while (i < to) {
                if (sb.length > limit) return true
                val (tag, i1) = varint(b, i, to) ?: return false
                i = i1
                val field = (tag ushr 3).toInt()
                val indent = "  ".repeat(depth)
                when ((tag and 7L).toInt()) {
                    0 -> {
                        val (v, i2) = varint(b, i, to) ?: return false
                        i = i2
                        sb.append(indent).append(field).append(": ").append(v).append('\n')
                    }
                    1 -> {
                        if (i + 8 > to) return false
                        var v = 0L
                        for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
                        i += 8
                        sb.append(indent).append(field).append(": 0x").append(java.lang.Long.toHexString(v)).append('\n')
                    }
                    5 -> {
                        if (i + 4 > to) return false
                        var v = 0L
                        for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
                        i += 4
                        sb.append(indent).append(field).append(": 0x").append(java.lang.Long.toHexString(v)).append('\n')
                    }
                    2 -> {
                        val (len, i2) = varint(b, i, to) ?: return false
                        val s = i2
                        val e = s + len.toInt()
                        if (len < 0 || e > to) return false
                        i = e
                        if (len == 0L) {
                            sb.append(indent).append(field).append(": \"\"\n")
                        } else if (isText(b, s, e)) {
                            sb.append(indent).append(field).append(": \"").append(String(b, s, (e - s), Charsets.UTF_8)).append("\"\n")
                        } else {
                            val mark = sb.length
                            sb.append(indent).append(field).append(" {\n")
                            val ok = depth < 8 && message(b, s, e, depth + 1, sb, limit)
                            if (ok) {
                                sb.append(indent).append("}\n")
                            } else {
                                sb.setLength(mark)
                                sb.append(indent).append(field).append(": <").append(e - s).append(" bytes>\n")
                            }
                        }
                    }
                    else -> return false
                }
            }
            return i == to
        }

        private fun isText(b: ByteArray, s: Int, e: Int): Boolean {
            for (k in s until e) {
                val c = b[k].toInt() and 0xff
                if (c < 0x20 && c != 0x0a && c != 0x0d && c != 0x09) return false
                if (c == 0x7f) return false
            }
            return true
        }

        private fun varint(b: ByteArray, from: Int, to: Int): Pair<Long, Int>? {
            var i = from
            var shift = 0
            var v = 0L
            while (i < to && shift < 64) {
                val c = b[i].toInt() and 0xff
                v = v or ((c and 0x7f).toLong() shl shift)
                i++
                if (c and 0x80 == 0) return v to i
                shift += 7
            }
            return null
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
