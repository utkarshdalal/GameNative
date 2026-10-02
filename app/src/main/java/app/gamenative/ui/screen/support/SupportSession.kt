package app.gamenative.ui.screen.support

import androidx.compose.runtime.mutableStateOf

object SupportSession {

    val pendingConversationId = mutableStateOf<String?>(null)

    @Volatile
    private var runTarget: Pair<String, String>? = null

    fun startedRunFrom(appId: String, conversationId: String) {
        runTarget = appId to conversationId
    }

    fun conversationForRun(appId: String): String? =
        runTarget?.takeIf { it.first == appId }?.second

    fun clearRun(appId: String) {
        if (runTarget?.first == appId) runTarget = null
    }
}
