package app.gamenative.html5.shim

import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.steam.handlers.steamauthticket.SteamAuthTicket
import `in`.dragonbra.javasteam.steam.handlers.steamauthticket.TicketInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import timber.log.Timber

// ISteamUser::GetAuthTicketForWebApi for a page's steamworks.js client, minted from the app's own Steam session
// for the launched game. the page asks, then polls: a mint is a few Steam round trips and must not block the
// page thread. a ticket stays valid until the page cancels it or the session ends. a page holds a few tickets at
// most and mints at a bounded rate, so a loop in it can't use up the session's game-connect tokens.
class SteamWebApiTickets(
    private val appId: Int,
    private val scope: CoroutineScope,
    private val mint: suspend (appId: Int, identity: String) -> TicketInfo? = ::mintFromSession,
    private val budget: TicketBudget = TicketBudget(),
) {
    private class Slot(val identity: String) {
        @Volatile var reply: String? = null
        @Volatile var ticket: TicketInfo? = null
        @Volatile var canceled = false
        // callers sharing one pending request; each cancels once.
        var holders = 1
    }

    private val seq = AtomicInteger()
    private val slots = LinkedHashMap<String, Slot>()

    // "" when refused (no app, too many tickets held, or asked too fast); the page's promise rejects then.
    fun request(identity: String): String {
        if (appId <= 0) return ""
        val id: String
        val slot: Slot
        synchronized(slots) {
            slots.entries.firstOrNull { (_, s) -> s.identity == identity && s.reply == null && !s.canceled }?.let {
                it.value.holders++
                return it.key
            }
            if (!budget.tryTake(outstanding = slots.size)) {
                Timber.tag(TAG).w("web api ticket refused for app %d: %d held", appId, slots.size)
                return ""
            }
            id = "t${seq.incrementAndGet()}"
            slot = Slot(identity)
            slots[id] = slot
        }
        scope.launch {
            val ticket = runCatching { mint(appId, identity) }
                .onFailure { Timber.tag(TAG).w(it, "web api ticket failed for app %d", appId) }
                .getOrNull()
            if (ticket == null) {
                slot.reply = JSONObject().put("error", "no ticket").toString()
                return@launch
            }
            if (slot.canceled) {
                runCatching { ticket.close() }
                return@launch
            }
            slot.ticket = ticket
            // a cancel that landed between the check above and here saw no ticket to close.
            if (slot.canceled) {
                runCatching { ticket.close() }
                return@launch
            }
            slot.reply = JSONObject().put("hex", WebApiTicketFormat.hex(WebApiTicketFormat.trim(ticket.ticket))).toString()
            Timber.tag(TAG).i("web api ticket ready for app %d", appId)
        }
        return id
    }

    // "" while pending. an error is handed out once per holder.
    fun take(id: String): String {
        synchronized(slots) {
            val slot = slots[id] ?: return JSONObject().put("error", "unknown ticket").toString()
            val reply = slot.reply ?: return ""
            if (slot.ticket == null && --slot.holders <= 0) slots.remove(id)
            return reply
        }
    }

    fun cancel(id: String) {
        val slot = synchronized(slots) {
            val s = slots[id] ?: return
            if (--s.holders > 0) return
            slots.remove(id)
            s
        }
        slot.canceled = true
        slot.ticket?.let { runCatching { it.close() } }
    }

    fun cancelAll() {
        val all = synchronized(slots) {
            val list = slots.values.toList()
            slots.clear()
            list
        }
        all.forEach { s ->
            s.canceled = true
            s.ticket?.let { runCatching { it.close() } }
        }
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

// at most maxOutstanding tickets held at once, and mints from a bucket of `burst` that refills one per refillMs.
class TicketBudget(
    private val maxOutstanding: Int = 4,
    private val burst: Int = 4,
    private val refillMs: Long = 15_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var tokens = burst.toDouble()
    private var last: Long? = null

    @Synchronized
    fun tryTake(outstanding: Int): Boolean {
        if (outstanding >= maxOutstanding) return false
        val t = now()
        last?.let { prev -> tokens = minOf(burst.toDouble(), tokens + (t - prev).coerceAtLeast(0) / refillMs.toDouble()) }
        last = t
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
