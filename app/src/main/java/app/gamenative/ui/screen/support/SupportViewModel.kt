package app.gamenative.ui.screen.support

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import app.gamenative.utils.DebugReportUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SupportViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    enum class Problem {
        NETWORK,
        UNAVAILABLE,
        UNAUTHORIZED,
        RATE_LIMITED,
        NOT_FOUND,
        SERVER,
        TOO_LONG,
        TOO_LARGE,
        NO_LOGS,
        LOCKED,
        ALREADY_ANSWERED,
    }

    data class ListState(
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val conversations: List<SupportApi.Conversation> = emptyList(),
        val problem: Problem? = null,
    )

    data class ChatState(
        val conversationId: String = "",
        val conversation: SupportApi.Conversation? = null,
        val messages: List<SupportApi.Message> = emptyList(),
        val cursor: Long = 0L,
        val loaded: Boolean = false,
        val problem: Problem? = null,
        val sending: Boolean = false,
        val actionProblem: Problem? = null,
        val outcomeBusy: Boolean = false,
    )

    var list by mutableStateOf(ListState())
        private set

    var chat by mutableStateOf(ChatState())
        private set

    var openConversationId by mutableStateOf(savedStateHandle.get<String>(KEY_OPEN))
        private set

    var draft by mutableStateOf(savedStateHandle.get<String>(KEY_DRAFT) ?: "")
        private set

    var uploadProgress by mutableStateOf<Float?>(null)
        private set

    var downloadingUrl by mutableStateOf<String?>(null)
        private set

    private val fetchMutex = Mutex()

    init {
        openConversationId?.let { chat = ChatState(conversationId = it) }
    }

    fun updateDraft(text: String) {
        val value = text.take(SupportApi.TEXT_MAX)
        draft = value
        savedStateHandle[KEY_DRAFT] = value
    }

    fun refreshList() {
        if (list.loading) return
        list = list.copy(loading = true, problem = null)
        viewModelScope.launch {
            list = when (val result = SupportApi.listConversations()) {
                is ApiResult.Success -> {
                    SupportReplyWatcher.observe(result.data)
                    ListState(loaded = true, conversations = result.data)
                }
                else -> list.copy(loading = false, problem = problemOf(result, notFound = Problem.UNAVAILABLE))
            }
        }
    }

    fun open(id: String) {
        if (openConversationId == id && chat.conversationId == id) return
        if (openConversationId != id) updateDraft("")
        openConversationId = id
        savedStateHandle[KEY_OPEN] = id
        chat = ChatState(conversationId = id, conversation = list.conversations.firstOrNull { it.id == id })
    }

    fun close() {
        openConversationId = null
        savedStateHandle.remove<String>(KEY_OPEN)
        chat = ChatState()
        updateDraft("")
        refreshList()
    }

    suspend fun poll(conversationId: String) {
        while (true) {
            val wait = fetchNew(conversationId)
            if (wait < 0) return
            delay(wait)
        }
    }

    private suspend fun fetchNew(conversationId: String): Long = fetchMutex.withLock {
        var pages = 0
        var wait: Long? = null
        while (wait == null) {
            pages++
            wait = fetchPage(conversationId, pages)
        }
        wait ?: -1L
    }

    private suspend fun fetchPage(conversationId: String, page: Int): Long? {
        if (chat.conversationId != conversationId) return -1L
        val result = SupportApi.messages(conversationId, chat.cursor, PAGE_SIZE)
        if (chat.conversationId != conversationId) return -1L
        return when (result) {
            is ApiResult.Success -> {
                merge(result.data)
                when {
                    result.data.messages.size >= PAGE_SIZE && page < MAX_PAGES -> null
                    chat.conversation?.progress?.active == true -> PROGRESS_POLL_MS
                    chat.conversation?.state == SupportApi.STATE_WAITING && chat.conversation?.progress == null -> WAITING_POLL_MS
                    else -> IDLE_POLL_MS
                }
            }
            else -> {
                val problem = problemOf(result)
                chat = chat.copy(problem = problem)
                when (problem) {
                    Problem.NOT_FOUND, Problem.UNAUTHORIZED -> -1L
                    Problem.RATE_LIMITED -> RATE_LIMITED_POLL_MS
                    else -> ERROR_POLL_MS
                }
            }
        }
    }

    private fun merge(page: SupportApi.MessagePage) {
        val known = chat.messages.mapTo(HashSet()) { it.id }
        val added = page.messages.filter { it.id !in known }
        val conversation = page.conversation ?: chat.conversation
        chat = chat.copy(
            messages = if (added.isEmpty()) chat.messages else (chat.messages + added).sortedBy { it.id },
            cursor = maxOf(chat.cursor, page.cursor),
            conversation = conversation,
            loaded = true,
            problem = null,
        )
        conversation?.let {
            updateListEntry(it)
            SupportReplyWatcher.markSeen(it.id, it.lastMessageAt)
        }
    }

    private fun updateListEntry(conversation: SupportApi.Conversation) {
        if (list.conversations.none { it.id == conversation.id }) return
        list = list.copy(conversations = list.conversations.map { if (it.id == conversation.id) conversation else it })
    }

    private fun applyPosted(posted: SupportApi.Posted) {
        val message = posted.message
        val messages = if (message != null && chat.messages.none { it.id == message.id }) {
            (chat.messages + message).sortedBy { it.id }
        } else {
            chat.messages
        }
        val conversation = posted.conversation?.let { update ->
            chat.conversation?.let { current ->
                update.copy(
                    game = update.game.ifEmpty { current.game },
                    appId = update.appId ?: current.appId,
                )
            } ?: update
        } ?: chat.conversation
        chat = chat.copy(messages = messages, conversation = conversation)
        conversation?.let {
            updateListEntry(it)
            if (it.awaitingReply) onFollowUpPosted(it)
        }
    }

    fun ingest(conversationId: String, posted: SupportApi.Posted) {
        if (chat.conversationId == conversationId) applyPosted(posted)
    }

    private fun onFollowUpPosted(conversation: SupportApi.Conversation) {
        SupportReportSubmitter.watchForReply(appContext, conversation, conversation.id, conversation.game)
    }

    private fun actionProblem(result: ApiResult<*>): Problem? {
        if (result is ApiResult.HttpError && result.code == 403) {
            val reason = result.message.ifBlank { SupportApi.REASON_UPGRADE_REQUIRED }
            chat.conversation?.let { current ->
                val resetsAt = if (SupportApi.isFairUse(reason)) SupportApi.lastFairUse?.resetsAt else null
                chat = chat.copy(conversation = current.copy(composer = SupportApi.Composer(false, reason, resetsAt)))
            }
            return if (reason == SupportApi.REASON_ANALYSING || SupportApi.isFairUse(reason)) null else Problem.LOCKED
        }
        return problemOf(result)
    }

    fun send() {
        val id = chat.conversationId
        val text = draft.trim()
        if (id.isEmpty() || text.isEmpty() || chat.sending || uploadProgress != null) return
        chat = chat.copy(sending = true, actionProblem = null)
        viewModelScope.launch {
            val result = SupportApi.postMessage(id, text)
            if (chat.conversationId != id) return@launch
            if (result is ApiResult.Success) {
                updateDraft("")
                applyPosted(result.data)
                chat = chat.copy(sending = false)
            } else {
                val problem = actionProblem(result)
                chat = chat.copy(sending = false, actionProblem = problem)
            }
        }
    }

    fun sendLogs(context: Context) {
        val id = chat.conversationId
        val appId = chat.conversation?.appId
        if (id.isEmpty() || uploadProgress != null || chat.sending) return
        if (appId.isNullOrEmpty()) {
            chat = chat.copy(actionProblem = Problem.NO_LOGS)
            return
        }
        val appContext = context.applicationContext
        uploadProgress = 0f
        chat = chat.copy(actionProblem = null)
        viewModelScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) { DebugReportUtils.newestReport(appContext, appId) }
                if (dir == null) {
                    chat = chat.copy(actionProblem = Problem.NO_LOGS)
                    return@launch
                }
                val header = withContext(Dispatchers.IO) { DebugReportUtils.readHeader(dir) }
                val text = draft.trim().ifEmpty { null }
                var lastReported = 0
                val result = SupportApi.uploadFiles(
                    id = id,
                    text = text,
                    report = header,
                    logFile = DebugReportUtils.logFile(dir),
                    perfFile = DebugReportUtils.perfFile(dir),
                    logcatFile = DebugReportUtils.logcatFile(dir),
                ) { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastReported) {
                        lastReported = percent
                        uploadProgress = progress
                    }
                }
                if (chat.conversationId != id) return@launch
                if (result is ApiResult.Success) {
                    withContext(Dispatchers.IO) { DebugReportUtils.deleteReport(dir) }
                    if (text != null) updateDraft("")
                    applyPosted(result.data)
                } else {
                    val problem = actionProblem(result)
                    chat = chat.copy(actionProblem = problem)
                }
            } finally {
                uploadProgress = null
            }
        }
    }

    fun setOutcome(solved: Boolean) {
        val id = chat.conversationId
        if (id.isEmpty() || chat.outcomeBusy) return
        chat = chat.copy(outcomeBusy = true, actionProblem = null)
        viewModelScope.launch {
            val result = SupportApi.setOutcome(id, solved)
            if (chat.conversationId != id) return@launch
            when {
                result is ApiResult.Success -> {
                    chat = chat.copy(conversation = result.data)
                    updateListEntry(result.data)
                }
                result is ApiResult.HttpError && result.code == 409 -> Unit
                else -> chat = chat.copy(actionProblem = problemOf(result))
            }
            fetchNew(id)
            chat = chat.copy(outcomeBusy = false)
        }
    }

    fun refreshAfterResume() {
        val id = chat.conversationId
        viewModelScope.launch {
            AccountApi.fetchAccount()
            if (id.isEmpty()) {
                refreshList()
                return@launch
            }
            fetchConversation(id)
        }
    }

    fun refreshConversation() {
        val id = chat.conversationId
        if (id.isEmpty()) return
        viewModelScope.launch { fetchConversation(id) }
    }

    private suspend fun fetchConversation(id: String) {
        val result = SupportApi.getConversation(id)
        if (result is ApiResult.Success && chat.conversationId == id) {
            chat = chat.copy(conversation = result.data)
            updateListEntry(result.data)
        }
    }

    fun clearActionProblem() {
        chat = chat.copy(actionProblem = null)
    }

    fun downloadAttachment(context: Context, attachment: SupportApi.Attachment, onReady: (File) -> Unit) {
        val url = attachment.url ?: return
        if (downloadingUrl != null) return
        val id = chat.conversationId
        val name = attachment.filename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "attachment" }
        val destination = File(context.cacheDir, "support_attachments/$id/$name")
        downloadingUrl = url
        viewModelScope.launch {
            val result = SupportApi.downloadAttachment(url, destination)
            downloadingUrl = null
            if (result is ApiResult.Success) {
                onReady(result.data)
            } else {
                chat = chat.copy(actionProblem = problemOf(result))
            }
        }
    }

    private fun problemOf(result: ApiResult<*>, notFound: Problem = Problem.NOT_FOUND): Problem =
        when (result) {
            is ApiResult.HttpError -> when (result.code) {
                401 -> Problem.UNAUTHORIZED
                404 -> notFound
                409 -> Problem.ALREADY_ANSWERED
                413 -> Problem.TOO_LARGE
                429 -> Problem.RATE_LIMITED
                400 -> if (result.message == "text_too_long") Problem.TOO_LONG else Problem.SERVER
                else -> Problem.SERVER
            }
            is ApiResult.NetworkError -> Problem.NETWORK
            is ApiResult.Success -> Problem.SERVER
        }

    companion object {
        private const val KEY_OPEN = "support_open_conversation"
        private const val KEY_DRAFT = "support_draft"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 10
        private const val WAITING_POLL_MS = 5_000L
        private const val PROGRESS_POLL_MS = 10_000L
        private const val IDLE_POLL_MS = 30_000L
        private const val ERROR_POLL_MS = 15_000L
        private const val RATE_LIMITED_POLL_MS = 60_000L
    }
}
