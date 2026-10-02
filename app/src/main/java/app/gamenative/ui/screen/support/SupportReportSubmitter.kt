package app.gamenative.ui.screen.support

import androidx.compose.runtime.mutableStateOf
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
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
        data class Failed(val reason: String) : Outcome()
    }

    val progress = mutableStateOf<Float?>(null)

    private fun hasPaidTier(): Boolean =
        AccountApi.account.value?.tier.let { it == "basic" || it == "pro" || it == "patron" }

    private fun outcomeOf(result: ApiResult<*>): Outcome =
        when (result) {
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

    suspend fun submit(state: DebugReportDialogState): Outcome {
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
                    return Outcome.Sent(target)
                }
                if (result !is ApiResult.HttpError || (result.code != 403 && result.code != 404)) return outcomeOf(result)
                SupportSession.clearRun(state.appId)
                progress.value = 0f
            }
            val created = SupportApi.createConversation(header, logFile, perfFile, logcatFile, onProgress)
            return if (created is ApiResult.Success) Outcome.Sent(created.data.id) else outcomeOf(created)
        } finally {
            progress.value = null
        }
    }
}
