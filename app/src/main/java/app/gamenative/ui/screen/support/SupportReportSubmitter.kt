package app.gamenative.ui.screen.support

import android.content.Context
import app.gamenative.R
import androidx.compose.runtime.mutableStateOf
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import app.gamenative.service.SupportReplyWatchService
import app.gamenative.ui.component.dialog.state.DebugReportDialogState
import app.gamenative.utils.DebugReportUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object SupportReportSubmitter {

    sealed class Outcome {
        data class Sent(val conversationId: String) : Outcome()
        data object Unavailable : Outcome()
        data object SignedOut : Outcome()
        data class Forbidden(val reason: String) : Outcome()
        data object PlanPending : Outcome()
        data object RateLimited : Outcome()
        data object LimitReached : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    val progress = mutableStateOf<Float?>(null)

    private fun hasPaidTier(): Boolean =
        AccountApi.account.value?.tier.let { it == "basic" || it == "pro" || it == "patron" }

    fun fairUseText(context: Context, hours: Int?): String =
        if (hours != null) {
            context.resources.getQuantityString(R.plurals.support_fair_use_resets_in_hours, hours, hours)
        } else {
            context.getString(R.string.support_upgrade_reason_reply_cap)
        }

    fun limitReachedText(context: Context): String {
        val text = fairUseText(context, SupportApi.lastFairUse?.hoursLeft())
        return if (AccountApi.account.value?.tier == "basic") {
            text + " " + context.getString(R.string.support_upgrade_reply_cap_pro_hint)
        } else {
            text
        }
    }

    private fun isLimit(result: ApiResult<*>): Boolean =
        result is ApiResult.HttpError && (
            (result.code == 403 && SupportApi.isFairUse(result.message)) ||
                (result.code == 429 && (SupportApi.isFairUse(result.message) || result.message == SupportApi.REASON_RATE_LIMITED))
            )

    private fun outcomeOf(result: ApiResult<*>): Outcome =
        if (isLimit(result)) Outcome.LimitReached else when (result) {
            is ApiResult.Success -> Outcome.Failed("unexpected")
            is ApiResult.NetworkError -> Outcome.Failed("network")
            is ApiResult.HttpError -> when (result.code) {
                401 -> Outcome.SignedOut
                403 -> if (hasPaidTier()) {
                    Outcome.PlanPending
                } else {
                    Outcome.Forbidden(result.message.ifBlank { SupportApi.REASON_NO_SUBSCRIPTION })
                }
                404 -> Outcome.Unavailable
                429 -> Outcome.RateLimited
                else -> Outcome.Failed(result.message.ifBlank { "http_${result.code}" })
            }
        }

    fun watchForReply(context: Context, conversation: SupportApi.Conversation?, conversationId: String, game: String) {
        val baseline = conversation?.lastMessageAt ?: 0L
        SupportReplyWatcher.track(conversationId, baseline)
        SupportReplyWatchService.start(context, conversationId, conversation?.game?.ifEmpty { null } ?: game, baseline)
    }

    suspend fun sendRun(
        context: Context,
        dir: File,
        conversationId: String,
        gameName: String,
        answer: () -> String?,
        onProgress: (Float) -> Unit,
    ): Pair<Outcome, SupportApi.Posted?> {
        val header: JSONObject = withContext(Dispatchers.IO) { DebugReportUtils.readHeader(dir) }
            ?: return Outcome.Failed("missing_report") to null
        val logFile = DebugReportUtils.logFile(dir)
        if (!logFile.exists()) return Outcome.Failed("missing_report") to null
        val perfFile = DebugReportUtils.perfFile(dir)
        val logcatFile = DebugReportUtils.logcatFile(dir)
        onProgress(0f)
        val result = SupportApi.uploadFilesWithLateText(conversationId, header, logFile, perfFile, logcatFile, answer, onProgress)
        if (result is ApiResult.Success) {
            watchForReply(context, result.data.conversation, conversationId, gameName)
            return Outcome.Sent(conversationId) to result.data
        }
        if (result !is ApiResult.HttpError || (result.code != 403 && result.code != 404) || isLimit(result)) return outcomeOf(result) to null
        onProgress(0f)
        val issue = answer().orEmpty()
        val createHeader = withContext(Dispatchers.IO) {
            if (DebugReportUtils.writeIssueText(dir, issue)) DebugReportUtils.readHeader(dir) else null
        } ?: header
        val created = SupportApi.createConversation(createHeader, logFile, perfFile, logcatFile, onProgress)
        if (created !is ApiResult.Success) return outcomeOf(created) to null
        watchForReply(context, created.data, created.data.id, gameName)
        return Outcome.Sent(created.data.id) to SupportApi.Posted(null, created.data)
    }

    suspend fun submit(context: Context, state: DebugReportDialogState): Outcome {
        val dir = File(state.reportDir)
        val header: JSONObject = withContext(Dispatchers.IO) {
            if (DebugReportUtils.writeIssueText(dir, state.issueText)) DebugReportUtils.readHeader(dir) else null
        } ?: return Outcome.Failed("missing_report")
        val logFile = DebugReportUtils.logFile(dir)
        if (!logFile.exists()) return Outcome.Failed("missing_report")
        val perfFile = DebugReportUtils.perfFile(dir)
        val logcatFile = DebugReportUtils.logcatFile(dir)
        val onProgress: (Float) -> Unit = { progress.value = it }
        progress.value = 0f
        try {
            val target = SupportSession.conversationForRun(state.appId)
            if (target != null) {
                val text = state.issueText.trim().take(SupportApi.TEXT_MAX).ifEmpty { null }
                val result = SupportApi.uploadFiles(target, text, header, logFile, perfFile, logcatFile, onProgress)
                if (result is ApiResult.Success) {
                    SupportSession.clearRun(state.appId)
                    watchForReply(context, result.data.conversation, target, state.gameName)
                    return Outcome.Sent(target)
                }
                if (result !is ApiResult.HttpError || (result.code != 403 && result.code != 404) || isLimit(result)) return outcomeOf(result)
                SupportSession.clearRun(state.appId)
                progress.value = 0f
            }
            val created = SupportApi.createConversation(header, logFile, perfFile, logcatFile, onProgress)
            if (created !is ApiResult.Success) return outcomeOf(created)
            watchForReply(context, created.data, created.data.id, state.gameName)
            return Outcome.Sent(created.data.id)
        } finally {
            progress.value = null
        }
    }
}
