package app.gamenative.ui.screen.support

import androidx.compose.runtime.mutableStateOf
import app.gamenative.PrefManager
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber

object SupportReplyWatcher {

    data class Ready(val conversationId: String, val game: String)

    private const val TAG = "SupportReplyWatcher"
    private const val POLL_MS = 60_000L
    private const val MAX_SEEN = 200

    val unread = mutableStateOf<Set<String>>(emptySet())

    val ready = mutableStateOf<Ready?>(null)

    @Volatile
    private var visibleChatId: String? = null

    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val watched = HashSet<String>()
    private var seen: MutableMap<String, Long>? = null
    private var lastPending = false

    fun kick() {
        kicks.trySend(Unit)
    }

    fun isChatVisible(conversationId: String): Boolean = visibleChatId == conversationId

    @Synchronized
    fun chatVisible(conversationId: String) {
        visibleChatId = conversationId
        watched.remove(conversationId)
        if (conversationId in unread.value) unread.value = unread.value - conversationId
        if (ready.value?.conversationId == conversationId) ready.value = null
    }

    @Synchronized
    fun chatHidden(conversationId: String) {
        if (visibleChatId == conversationId) visibleChatId = null
    }

    @Synchronized
    fun track(conversationId: String, lastMessageAt: Long) {
        watched.add(conversationId)
        val map = seenMap()
        if ((map[conversationId] ?: -1L) < lastMessageAt) {
            map[conversationId] = lastMessageAt
            save(map)
        }
        kick()
    }

    @Synchronized
    fun markSeen(conversationId: String, at: Long) {
        val map = seenMap()
        if ((map[conversationId] ?: -1L) < at) {
            map[conversationId] = at
            save(map)
        }
        if (conversationId in unread.value) unread.value = unread.value - conversationId
    }

    @Synchronized
    fun observe(conversations: List<SupportApi.Conversation>) {
        try {
            val map = seenMap()
            var changed = false
            val nowUnread = HashSet<String>()
            for (conversation in conversations) {
                val id = conversation.id
                val awaiting = conversation.awaitingReply
                if (map[id] == null) {
                    map[id] = if (awaiting) 0L else conversation.lastMessageAt
                    changed = true
                }
                if (awaiting) {
                    watched.add(id)
                    continue
                }
                if (id == visibleChatId) {
                    watched.remove(id)
                    if ((map[id] ?: 0L) < conversation.lastMessageAt) {
                        map[id] = conversation.lastMessageAt
                        changed = true
                    }
                    continue
                }
                val replied = conversation.state != SupportApi.STATE_WAITING &&
                    conversation.lastMessageAt > (map[id] ?: 0L)
                if (replied) {
                    nowUnread.add(id)
                    if (watched.remove(id)) ready.value = Ready(id, conversation.game)
                }
            }
            if (map.size > MAX_SEEN) {
                val present = conversations.mapTo(HashSet()) { it.id }
                map.keys.retainAll(present)
                changed = true
            }
            if (changed) save(map)
            if (nowUnread != unread.value) unread.value = nowUnread
            lastPending = conversations.any { it.awaitingReply }
        } catch (e: Exception) {
            Timber.tag(TAG).w("observe failed: ${e.javaClass.simpleName}")
        }
    }

    suspend fun run() {
        while (true) {
            val pending = try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w("poll failed: ${e.javaClass.simpleName}")
                false
            }
            if (pending) {
                withTimeoutOrNull(POLL_MS) { kicks.receive() }
            } else {
                kicks.receive()
            }
        }
    }

    private suspend fun pollOnce(): Boolean {
        if (!PrefManager.gameNativeSignedIn.value || SupportApi.available.value == false) return false
        if (!hasHistory()) return false
        return when (val result = SupportApi.listConversations()) {
            is ApiResult.Success -> {
                observe(result.data)
                lastPending
            }
            is ApiResult.NetworkError -> lastPending
            is ApiResult.HttpError -> false
        }
    }

    @Synchronized
    private fun hasHistory(): Boolean = watched.isNotEmpty() || seenMap().isNotEmpty()

    private fun seenMap(): MutableMap<String, Long> {
        seen?.let { return it }
        val loaded = HashMap<String, Long>()
        try {
            val raw = PrefManager.supportLastSeen
            if (raw.isNotEmpty()) {
                val json = JSONObject(raw)
                json.keys().forEach { key -> loaded[key] = json.optLong(key, 0L) }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("last-seen read failed: ${e.javaClass.simpleName}")
        }
        seen = loaded
        return loaded
    }

    private fun save(map: Map<String, Long>) {
        try {
            val json = JSONObject()
            map.forEach { (key, value) -> json.put(key, value) }
            PrefManager.supportLastSeen = json.toString()
        } catch (e: Exception) {
            Timber.tag(TAG).w("last-seen write failed: ${e.javaClass.simpleName}")
        }
    }
}
