package app.gamenative.ui.screen.support

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DebugReportUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object SupportRunFollowUp {

    enum class Phase { PREPARING, UPLOADING, SENT, FAILED, NO_LOG, OFFER }

    enum class Answer { NONE, WAITING, SENDING, DELIVERED, FAILED }

    data class State(
        val appId: String,
        val conversationId: String,
        val phase: Phase,
        val progress: Float? = null,
        val reportDir: String? = null,
        val failure: SupportReportSubmitter.Outcome? = null,
        val answer: Answer = Answer.NONE,
        val dismissed: Boolean = false,
    ) {
        val asking: Boolean
            get() = answer == Answer.NONE && phase in ASKING_PHASES

        val busy: Boolean
            get() = phase == Phase.PREPARING || phase == Phase.UPLOADING

        val takesReply: Boolean
            get() = asking || (busy && answer == Answer.WAITING)
    }

    private val ASKING_PHASES = setOf(Phase.PREPARING, Phase.UPLOADING, Phase.SENT, Phase.FAILED)

    val state = mutableStateOf<State?>(null)

    private val _updates = MutableSharedFlow<Pair<String, SupportApi.Posted>>(extraBufferCapacity = 8)
    val updates: SharedFlow<Pair<String, SupportApi.Posted>> = _updates

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var gameName = ""
    private val skippedReports = mutableSetOf<String>()

    @Volatile
    private var answerText: String? = null

    @Volatile
    private var includedText: String? = null

    private fun update(transform: (State) -> State) {
        state.value?.let { state.value = transform(it) }
    }

    fun start(context: Context, appId: String, conversationId: String, pending: Deferred<File?>?) {
        val appContext = context.applicationContext
        job?.cancel()
        answerText = null
        includedText = null
        state.value = State(appId = appId, conversationId = conversationId, phase = Phase.PREPARING)
        job = scope.launch {
            gameName = withContext(Dispatchers.IO) { ContainerUtils.resolveGameName(appId) }
            val dir = pending?.await() ?: withContext(Dispatchers.IO) { DebugReportUtils.newestReport(appContext, appId) }
            if (dir == null) {
                SupportSession.clearRun(appId)
                update { it.copy(phase = Phase.NO_LOG) }
                return@launch
            }
            upload(appContext, dir)
        }
    }

    fun offerLastRun(context: Context, appId: String, conversationId: String, lastUploadAt: Long) {
        if (state.value != null) return
        val appContext = context.applicationContext
        scope.launch {
            val dir = withContext(Dispatchers.IO) { DebugReportUtils.newestReport(appContext, appId) } ?: return@launch
            val createdAt = dir.name.removePrefix("${appId}_").toLongOrNull() ?: return@launch
            if (createdAt <= lastUploadAt || dir.name in skippedReports || state.value != null) return@launch
            answerText = null
            includedText = null
            state.value = State(
                appId = appId,
                conversationId = conversationId,
                phase = Phase.OFFER,
                reportDir = dir.absolutePath,
            )
        }
    }

    fun sendOffered(context: Context) {
        val current = state.value ?: return
        if (current.phase != Phase.OFFER) return
        val dir = current.reportDir?.let { File(it) } ?: return
        val appContext = context.applicationContext
        job?.cancel()
        job = scope.launch {
            gameName = withContext(Dispatchers.IO) { ContainerUtils.resolveGameName(current.appId) }
            upload(appContext, dir)
        }
    }

    fun retry(context: Context) {
        val current = state.value ?: return
        if (current.phase != Phase.FAILED) return
        val dir = current.reportDir?.let { File(it) }?.takeIf { it.exists() } ?: return
        val appContext = context.applicationContext
        job?.cancel()
        job = scope.launch { upload(appContext, dir) }
    }

    private suspend fun upload(context: Context, dir: File) {
        val current = state.value ?: return
        includedText = null
        update { it.copy(phase = Phase.UPLOADING, progress = 0f, reportDir = dir.absolutePath, failure = null) }
        var lastPercent = -1
        val (outcome, posted) = SupportReportSubmitter.sendRun(
            context = context,
            dir = dir,
            conversationId = current.conversationId,
            gameName = gameName,
            answer = { answerText.also { includedText = it } },
            onProgress = { progress ->
                val percent = (progress * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    scope.launch { update { if (it.phase == Phase.UPLOADING) it.copy(progress = progress) else it } }
                }
            },
        )
        if (outcome !is SupportReportSubmitter.Outcome.Sent) {
            update { it.copy(phase = Phase.FAILED, progress = null, failure = outcome) }
            return
        }
        withContext(Dispatchers.IO) {
            val header = DebugReportUtils.readHeader(dir)
            DebugReportUtils.deleteReport(dir)
            SupportAppliedRun.recordReportedRun(context, current.appId, header)
        }
        SupportSession.clearRun(current.appId)
        val target = outcome.conversationId
        posted?.let { _updates.tryEmit(target to it) }
        if (target != current.conversationId) SupportSession.pendingConversationId.value = target
        update { it.copy(conversationId = target, phase = Phase.SENT, progress = null) }
        val pendingAnswer = answerText
        val included = includedText
        val remainder = when {
            pendingAnswer == null -> null
            included.isNullOrEmpty() -> pendingAnswer
            pendingAnswer.startsWith(included) -> pendingAnswer.removePrefix(included).trim()
            else -> pendingAnswer
        }
        when {
            pendingAnswer == null -> Unit
            remainder.isNullOrEmpty() -> update { it.copy(answer = Answer.DELIVERED) }
            else -> {
                answerText = remainder
                postAnswer(context, remainder)
            }
        }
        finishIfDone()
    }

    fun answer(context: Context, text: String, worked: Boolean?, recordOutcome: Boolean, details: String = "") {
        val current = state.value ?: return
        val value = text.trim().take(SupportApi.TEXT_MAX)
        if (value.isEmpty()) return
        if (current.busy && current.answer == Answer.WAITING) {
            answerText = listOfNotNull(answerText, value).joinToString("\n").take(SupportApi.TEXT_MAX)
            return
        }
        if (!current.asking) return
        answerText = value
        update { it.copy(answer = Answer.WAITING) }
        val appContext = context.applicationContext
        if (worked != null && recordOutcome) {
            val conversationId = current.conversationId
            val afterUpload = current.phase == Phase.SENT
            if (afterUpload) update { it.copy(answer = Answer.SENDING) }
            scope.launch {
                val result = SupportApi.setOutcome(conversationId, worked)
                if (result is ApiResult.Success) _updates.tryEmit(conversationId to SupportApi.Posted(null, result.data))
                if (!afterUpload) return@launch
                val extra = details.trim().take(SupportApi.TEXT_MAX)
                when {
                    result !is ApiResult.Success -> postAnswer(appContext, value)
                    extra.isNotEmpty() -> {
                        answerText = extra
                        postAnswer(appContext, extra)
                    }
                    else -> {
                        update { it.copy(answer = Answer.DELIVERED) }
                        finishIfDone()
                    }
                }
            }
            if (afterUpload) return
        }
        when (current.phase) {
            Phase.SENT -> postAnswer(appContext, value)
            Phase.FAILED -> retry(appContext)
            else -> Unit
        }
    }

    fun retryAnswer(context: Context) {
        val current = state.value ?: return
        val text = answerText ?: return
        if (current.answer != Answer.FAILED) return
        postAnswer(context.applicationContext, text)
    }

    private fun postAnswer(context: Context, text: String) {
        val conversationId = state.value?.conversationId ?: return
        update { it.copy(answer = Answer.SENDING) }
        scope.launch {
            val result = SupportApi.postMessage(conversationId, text)
            if (result is ApiResult.Success) {
                _updates.tryEmit(conversationId to result.data)
                SupportReportSubmitter.watchForReply(context, result.data.conversation, conversationId, gameName)
                update { if (it.conversationId == conversationId) it.copy(answer = Answer.DELIVERED) else it }
                finishIfDone()
            } else {
                update { if (it.conversationId == conversationId) it.copy(answer = Answer.FAILED) else it }
            }
        }
    }

    private fun finishIfDone() {
        val current = state.value ?: return
        if (current.phase != Phase.SENT) return
        if (current.answer == Answer.DELIVERED || (current.dismissed && current.answer == Answer.NONE)) state.value = null
    }

    fun clear(conversationId: String) {
        if (state.value?.conversationId != conversationId) return
        job?.cancel()
        state.value = null
    }

    fun dismiss(conversationId: String) {
        val current = state.value ?: return
        if (current.conversationId != conversationId) return
        if (current.phase == Phase.OFFER) {
            current.reportDir?.let { skippedReports.add(File(it).name) }
            state.value = null
            return
        }
        SupportSession.clearRun(current.appId)
        if (current.busy || current.answer == Answer.SENDING || current.answer == Answer.WAITING) {
            update { it.copy(dismissed = true) }
            return
        }
        state.value = null
    }
}
