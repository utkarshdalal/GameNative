package app.gamenative.api

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.Instant

object SupportApi {

    const val BASE_URL = "${DebugReportApi.RELAY_BASE_URL}/api/app"
    const val TEXT_MAX = 1800
    const val NOT_SIGNED_IN = "not_signed_in"

    const val KIND_USER = "user"
    const val KIND_AGENT = "agent"
    const val KIND_STAFF = "staff"
    const val KIND_NOTICE = "notice"

    const val STATE_WAITING = "waiting"
    const val STATE_ANSWERED = "answered"
    const val STATE_SOLVED = "solved"

    const val REASON_UPGRADE_REQUIRED = "upgrade_required"
    const val REASON_REPLY_CAP = "reply_cap"
    const val REASON_FAIR_USE = "fair_use"
    const val REASON_TRIAL_USED = "trial_used"
    const val REASON_ANALYSING = "analysing"
    const val REASON_NO_SUBSCRIPTION = "no_subscription"
    const val REASON_RATE_LIMITED = "rate_limited"
    const val REASON_ALREADY_ANSWERED = "already_answered"

    const val STAGE_QUEUED = "queued"
    const val STAGE_ANALYSING = "analysing"
    const val STAGE_ANSWERED = "answered"
    const val STAGE_FAILED = "failed"

    private const val TAG = "SupportApi"
    private const val ATTACHMENT_PREFIX = "/api/app/conversations/"
    private const val UNAVAILABLE_RECHECK_MS = 10 * 60 * 1000L

    data class Composer(val allowed: Boolean, val reason: String?, val resetsAt: Long? = null)

    data class FairUse(val resetsAt: Long?, val resetsInHours: Int?) {
        fun hoursLeft(now: Long = System.currentTimeMillis()): Int? =
            resetsInHours?.takeIf { it > 0 }
                ?: resetsAt?.let { at -> if (at > now) ((at - now + 3_599_999L) / 3_600_000L).toInt().coerceAtLeast(1) else null }
    }

    fun isFairUse(reason: String?): Boolean = reason == REASON_FAIR_USE || reason == REASON_REPLY_CAP

    @Volatile
    var lastFairUse: FairUse? = null
        private set

    data class Progress(
        val stage: String,
        val position: Int?,
        val etaSeconds: Long?,
        val startedAt: Long,
        val updatedAt: Long,
        val detail: String?,
        val receivedAt: Long = SystemClock.elapsedRealtime(),
    ) {
        val active: Boolean get() = stage == STAGE_QUEUED || stage == STAGE_ANALYSING
    }

    data class Conversation(
        val id: String,
        val game: String,
        val appId: String?,
        val tier: String,
        val state: String,
        val outcome: Boolean?,
        val createdAt: Long,
        val lastMessageAt: Long,
        val composer: Composer,
        val progress: Progress? = null,
    ) {
        val awaitingReply: Boolean
            get() = progress?.active ?: (state == STATE_WAITING)
    }

    data class Attachment(val filename: String, val size: Long?, val url: String?)

    sealed class Notice {
        data class Upgrade(val reason: String) : Notice()
        data class Moved(val tier: String) : Notice()
        data class Outcome(val solved: Boolean, val note: String?) : Notice()
        data class Limit(val reason: String, val resetsAt: Long?, val message: String?) : Notice()
        data class Other(val type: String) : Notice()
    }

    data class Message(
        val id: Long,
        val kind: String,
        val text: String,
        val createdAt: Long,
        val authorName: String?,
        val isReport: Boolean,
        val attachments: List<Attachment>,
        val notice: Notice?,
        val suggestion: SupportSuggestion? = null,
    )

    data class MessagePage(
        val messages: List<Message>,
        val cursor: Long,
        val conversation: Conversation?,
    )

    data class Posted(val message: Message?, val conversation: Conversation?)

    private data class Raw(val code: Int, val body: String)

    val available = mutableStateOf<Boolean?>(null)

    private var unavailableSince = 0L

    private fun markAvailability(code: Int) {
        when {
            code == 404 -> {
                available.value = false
                unavailableSince = SystemClock.elapsedRealtime()
            }
            code in 200..299 -> available.value = true
        }
    }

    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }

    private fun parseTime(value: String?): Long =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private fun errorReason(body: String): String =
        try {
            val json = JSONObject(body)
            val reason = json.str("error") ?: json.str("reason") ?: ""
            if (isFairUse(reason) || isFairUse(json.str("reason"))) {
                lastFairUse = FairUse(
                    resetsAt = json.time("resets_at").takeIf { it > 0 },
                    resetsInHours = if (json.isNull("resets_in_hours")) null else json.optInt("resets_in_hours", -1).takeIf { it > 0 },
                )
                REASON_FAIR_USE
            } else {
                reason
            }
        } catch (_: JSONException) {
            ""
        }

    private fun logFailure(name: String, e: Exception) {
        if (e is JSONException) {
            Timber.tag(TAG).e("$name returned an unreadable body")
        } else {
            Timber.tag(TAG).w("$name failed: ${e.javaClass.simpleName}")
        }
    }

    private fun JSONObject.time(name: String): Long =
        when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: parseTime(value)
            else -> 0L
        }

    private fun parseProgress(json: JSONObject?): Progress? {
        if (json == null) return null
        val stage = json.str("stage") ?: return null
        val position = if (json.has("position") && !json.isNull("position")) json.optInt("position", -1).takeIf { it >= 0 } else null
        val eta = if (json.has("etaSeconds") && !json.isNull("etaSeconds")) json.optLong("etaSeconds", -1L).takeIf { it >= 0 } else null
        return Progress(
            stage = stage,
            position = position,
            etaSeconds = eta,
            startedAt = json.time("startedAt"),
            updatedAt = json.time("updatedAt"),
            detail = json.str("detail")?.take(200),
        )
    }

    private fun parseConversation(json: JSONObject): Conversation {
        val composer = json.optJSONObject("composer")
        return Conversation(
            id = json.getString("id"),
            game = json.str("game") ?: "",
            appId = json.str("app_id"),
            tier = json.str("tier") ?: "",
            state = json.str("state") ?: STATE_WAITING,
            outcome = if (json.isNull("outcome")) null else json.optBoolean("outcome"),
            createdAt = parseTime(json.str("created_at")),
            lastMessageAt = parseTime(json.str("last_message_at")),
            composer = Composer(
                allowed = composer?.optBoolean("allowed", false) ?: false,
                reason = composer?.str("reason"),
                resetsAt = composer?.time("resets_at")?.takeIf { it > 0 },
            ),
            progress = runCatching { parseProgress(json.optJSONObject("progress")) }.getOrNull(),
        )
    }

    private fun parseNotice(json: JSONObject): Notice =
        when (val type = json.str("type") ?: "") {
            "upgrade" -> Notice.Upgrade(json.str("reason") ?: REASON_UPGRADE_REQUIRED)
            "moved" -> Notice.Moved(json.str("tier") ?: "pro")
            "outcome" -> Notice.Outcome(json.optBoolean("solved", false), json.str("note"))
            "limit" -> Notice.Limit(
                reason = json.str("reason") ?: REASON_FAIR_USE,
                resetsAt = json.time("resets_at").takeIf { it > 0 },
                message = json.str("message"),
            )
            else -> Notice.Other(type)
        }

    private fun parseMessage(json: JSONObject): Message? {
        val id = json.optLong("id", -1L)
        val kind = json.str("kind")
        if (id < 0 || kind == null) return null
        val attachmentArray = json.optJSONArray("attachments")
        val attachments = buildList {
            if (attachmentArray != null) {
                for (i in 0 until attachmentArray.length()) {
                    val item = attachmentArray.optJSONObject(i) ?: continue
                    add(
                        Attachment(
                            filename = item.str("filename") ?: "file",
                            size = if (item.isNull("size")) null else item.optLong("size"),
                            url = item.str("url"),
                        ),
                    )
                }
            }
        }
        return Message(
            id = id,
            kind = kind,
            text = json.str("text") ?: "",
            createdAt = parseTime(json.str("created_at")),
            authorName = json.optJSONObject("author")?.str("name"),
            isReport = json.optBoolean("report", false),
            attachments = attachments,
            notice = json.optJSONObject("notice")?.let { parseNotice(it) },
            suggestion = if (kind == KIND_AGENT) {
                runCatching { SupportSuggestion.parse(json.optJSONObject("suggestion")) }.getOrNull()
            } else {
                null
            },
        )
    }

    private fun parsePosted(json: JSONObject): Posted =
        Posted(
            message = json.optJSONObject("message")?.let { parseMessage(it) },
            conversation = json.optJSONObject("conversation")?.let { parseConversation(it) },
        )

    private suspend fun <T> call(
        name: String,
        tracksAvailability: Boolean = false,
        build: (Request.Builder) -> Request.Builder,
        parse: (JSONObject) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val raw = AccountApi.sendAuthorized(build) { Raw(it.code, it.body.string()) }
                ?: return@withContext ApiResult.HttpError(401, NOT_SIGNED_IN)
            if (tracksAvailability) markAvailability(raw.code)
            if (raw.code !in 200..299) {
                val reason = errorReason(raw.body)
                Timber.tag(TAG).w("$name HTTP ${raw.code}: $reason")
                return@withContext ApiResult.HttpError(raw.code, reason)
            }
            ApiResult.Success(parse(JSONObject(raw.body)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(name, e)
            ApiResult.NetworkError(e)
        }
    }

    suspend fun checkAvailability(): Boolean? = withContext(Dispatchers.IO) {
        when (available.value) {
            true -> return@withContext true
            false -> if (SystemClock.elapsedRealtime() - unavailableSince < UNAVAILABLE_RECHECK_MS) return@withContext false
            null -> Unit
        }
        try {
            val request = Request.Builder().url("$BASE_URL/status").get().build()
            val result = GameNativeApi.httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> false
                    in 200..299 -> runCatching { JSONObject(response.body.string()).optBoolean("enabled", false) }.getOrNull()
                    else -> null
                }
            }
            if (result != null) markAvailability(if (result) 200 else 404)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("availability", e)
            null
        }
    }

    private fun multipart(
        report: JSONObject?,
        logFile: File?,
        perfFile: File?,
        logcatFile: File?,
        text: String?,
    ): MultipartBody {
        val builder = multipartBuilder(report, logFile, perfFile, logcatFile)
        if (!text.isNullOrBlank()) {
            builder.addFormDataPart("text", text)
        }
        return builder.build()
    }

    private fun multipartBuilder(
        report: JSONObject?,
        logFile: File?,
        perfFile: File?,
        logcatFile: File?,
    ): MultipartBody.Builder {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (report != null) {
            builder.addFormDataPart("report", null, report.toString().toRequestBody("application/json".toMediaType()))
        }
        if (logFile != null && logFile.exists()) {
            builder.addFormDataPart("log", "log.gz", logFile.asRequestBody("application/gzip".toMediaType()))
        }
        if (perfFile != null && perfFile.exists()) {
            builder.addFormDataPart("perf", "perf.json", perfFile.asRequestBody("application/json".toMediaType()))
        }
        if (logcatFile != null && logcatFile.exists()) {
            builder.addFormDataPart("logcat", "logcat.gz", logcatFile.asRequestBody("application/gzip".toMediaType()))
        }
        return builder
    }

    private class LateTextBody(private val text: () -> String?) : RequestBody() {
        override fun contentType(): MediaType? = "text/plain; charset=utf-8".toMediaType()

        override fun contentLength(): Long = -1L

        override fun writeTo(sink: BufferedSink) {
            text()?.take(TEXT_MAX)?.let { sink.writeUtf8(it) }
        }
    }

    private class ProgressBody(
        private val delegate: RequestBody,
        private val onProgress: (Float) -> Unit,
        private val expectedLength: Long = -1L,
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength().takeIf { it > 0 } ?: expectedLength
            var written = 0L
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            val buffered = counting.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    private fun withProgress(body: RequestBody, onProgress: ((Float) -> Unit)?): RequestBody =
        if (onProgress == null) body else ProgressBody(body, onProgress)

    suspend fun createConversation(
        report: JSONObject,
        logFile: File,
        perfFile: File?,
        logcatFile: File?,
        onProgress: ((Float) -> Unit)? = null,
    ): ApiResult<Conversation> {
        val body = withProgress(multipart(report, logFile, perfFile, logcatFile, null), onProgress)
        return call(
            name = "conversations/create",
            tracksAvailability = true,
            build = { it.url("$BASE_URL/conversations").post(body) },
            parse = { parseConversation(it.getJSONObject("conversation")) },
        )
    }

    suspend fun listConversations(): ApiResult<List<Conversation>> =
        call(
            name = "conversations/list",
            tracksAvailability = true,
            build = { it.url("$BASE_URL/conversations").get() },
            parse = { json ->
                val array = json.optJSONArray("conversations")
                buildList {
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            runCatching { parseConversation(item) }.getOrNull()?.let { add(it) }
                        }
                    }
                }
            },
        )

    suspend fun getConversation(id: String): ApiResult<Conversation> =
        call(
            name = "conversations/get",
            build = { it.url("$BASE_URL/conversations/$id").get() },
            parse = { parseConversation(it.getJSONObject("conversation")) },
        )

    suspend fun messages(id: String, after: Long, limit: Int = 100): ApiResult<MessagePage> =
        call(
            name = "conversations/messages",
            build = { it.url("$BASE_URL/conversations/$id/messages?after=$after&limit=$limit").get() },
            parse = { json ->
                val array = json.optJSONArray("messages")
                val messages = buildList {
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            array.optJSONObject(i)?.let { parseMessage(it) }?.let { add(it) }
                        }
                    }
                }
                MessagePage(
                    messages = messages,
                    cursor = json.optLong("cursor", messages.lastOrNull()?.id ?: after),
                    conversation = json.optJSONObject("conversation")?.let { runCatching { parseConversation(it) }.getOrNull() },
                )
            },
        )

    suspend fun postMessage(id: String, text: String): ApiResult<Posted> =
        call(
            name = "conversations/post",
            build = {
                it.url("$BASE_URL/conversations/$id/messages")
                    .post(JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType()))
            },
            parse = { parsePosted(it) },
        )

    suspend fun uploadFiles(
        id: String,
        text: String?,
        report: JSONObject?,
        logFile: File?,
        perfFile: File?,
        logcatFile: File?,
        onProgress: ((Float) -> Unit)? = null,
    ): ApiResult<Posted> {
        val body = withProgress(multipart(report, logFile, perfFile, logcatFile, text), onProgress)
        return call(
            name = "conversations/files",
            build = { it.url("$BASE_URL/conversations/$id/files").post(body) },
            parse = { parsePosted(it) },
        )
    }

    suspend fun uploadFilesWithLateText(
        id: String,
        report: JSONObject?,
        logFile: File?,
        perfFile: File?,
        logcatFile: File?,
        text: () -> String?,
        onProgress: ((Float) -> Unit)? = null,
    ): ApiResult<Posted> {
        val builder = multipartBuilder(report, logFile, perfFile, logcatFile)
        builder.addFormDataPart("text", null, LateTextBody(text))
        val multipart = builder.build()
        val expected = listOfNotNull(logFile, perfFile, logcatFile).filter { it.exists() }.sumOf { it.length() }
        val body = if (onProgress == null) multipart else ProgressBody(multipart, onProgress, expected)
        return call(
            name = "conversations/files",
            build = { it.url("$BASE_URL/conversations/$id/files").post(body) },
            parse = { parsePosted(it) },
        )
    }

    suspend fun setOutcome(id: String, solved: Boolean, note: String? = null): ApiResult<Conversation> =
        call(
            name = "conversations/outcome",
            build = {
                val json = JSONObject().put("solved", solved)
                if (!note.isNullOrBlank()) json.put("note", note.take(1000))
                it.url("$BASE_URL/conversations/$id/outcome")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
            },
            parse = { parseConversation(it.getJSONObject("conversation")) },
        )

    suspend fun downloadAttachment(path: String, destination: File): ApiResult<File> = withContext(Dispatchers.IO) {
        if (!path.startsWith(ATTACHMENT_PREFIX) || path.contains("..")) {
            return@withContext ApiResult.HttpError(400, "bad_attachment")
        }
        try {
            destination.parentFile?.mkdirs()
            val code = AccountApi.sendAuthorized({ it.url(DebugReportApi.RELAY_BASE_URL + path).get() }) { response ->
                if (response.isSuccessful) {
                    response.body.byteStream().use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                response.code
            } ?: return@withContext ApiResult.HttpError(401, NOT_SIGNED_IN)
            if (code !in 200..299) {
                destination.delete()
                Timber.tag(TAG).w("attachment HTTP $code")
                return@withContext ApiResult.HttpError(code, "")
            }
            ApiResult.Success(destination)
        } catch (e: CancellationException) {
            destination.delete()
            throw e
        } catch (e: Exception) {
            destination.delete()
            logFailure("attachment", e)
            ApiResult.NetworkError(e)
        }
    }
}
