package app.gamenative.html5.shim

import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.steam.handlers.steamauthticket.SteamAuthTicket
import `in`.dragonbra.javasteam.steam.handlers.steamauthticket.TicketInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import timber.log.Timber

// ISteamUser::GetAuthTicketForWebApi for a page's steamworks.js client, minted from the app's own Steam session
// for the launched game. the page asks, then polls: a mint is a few Steam round trips and must not block the
// page thread. a ticket stays valid until the page cancels it or the session ends.
class SteamWebApiTickets(
    private val appId: Int,
    private val scope: CoroutineScope,
    private val mint: suspend (appId: Int, identity: String) -> TicketInfo? = ::mintFromSession,
) {
    private class Slot {
        @Volatile var reply: String? = null
        @Volatile var ticket: TicketInfo? = null
        @Volatile var cancelled = false
    }

    private val seq = AtomicInteger()
    private val slots = ConcurrentHashMap<String, Slot>()

    fun request(identity: String): String {
        if (appId <= 0) return ""
        val id = "t${seq.incrementAndGet()}"
        val slot = Slot()
        slots[id] = slot
        scope.launch {
            val ticket = runCatching { mint(appId, identity) }
                .onFailure { Timber.tag(TAG).w(it, "web api ticket failed for app %d", appId) }
                .getOrNull()
            if (ticket == null) {
                slot.reply = JSONObject().put("error", "no ticket").toString()
                return@launch
            }
            if (slot.cancelled) {
                runCatching { ticket.close() }
                return@launch
            }
            slot.ticket = ticket
            // a cancel that landed between the check above and here saw no ticket to close.
            if (slot.cancelled) {
                runCatching { ticket.close() }
                return@launch
            }
            slot.reply = JSONObject().put("hex", WebApiTicketFormat.hex(WebApiTicketFormat.trim(ticket.ticket))).toString()
            Timber.tag(TAG).i("web api ticket ready for app %d", appId)
        }
        return id
    }

    // "" while pending. an error is handed out once.
    fun take(id: String): String {
        val slot = slots[id] ?: return JSONObject().put("error", "unknown ticket").toString()
        val reply = slot.reply ?: return ""
        if (slot.ticket == null) slots.remove(id)
        return reply
    }

    fun cancel(id: String) {
        val slot = slots.remove(id) ?: return
        slot.cancelled = true
        slot.ticket?.let { runCatching { it.close() } }
    }

    fun cancelAll() {
        slots.keys.toList().forEach(::cancel)
    }

    companion object {
        private const val TAG = "SteamWebApiTickets"

        private suspend fun mintFromSession(appId: Int, identity: String): TicketInfo? {
            val client = SteamService.instance?.steamClient ?: return null
            if (client.steamID?.isValid != true) return null
            val handler = client.getHandler(SteamAuthTicket::class.java) ?: return null
            return withTimeout(20_000) { handler.getAuthTicketForWebApi(appId, identity).await() }
        }
    }
}
