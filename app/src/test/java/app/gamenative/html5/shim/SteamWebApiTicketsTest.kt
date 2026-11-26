package app.gamenative.html5.shim

import `in`.dragonbra.javasteam.steam.handlers.steamauthticket.TicketInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamWebApiTicketsTest {

    private var clock = 0L
    private var mints = 0
    private val pending = CompletableDeferred<TicketInfo?>()

    private fun tickets(budget: TicketBudget = TicketBudget(now = { clock })) = SteamWebApiTickets(
        appId = 4705510,
        scope = CoroutineScope(Dispatchers.Unconfined),
        mint = { _, _ ->
            mints++
            pending.await()
        },
        budget = budget,
    )

    @Test
    fun aPendingRequestForTheSameIdentityIsShared() {
        val t = tickets()
        val a = t.request("site")
        val b = t.request("site")
        assertEquals(a, b)
        assertEquals(1, mints)
        assertNotEquals(a, t.request("other"))
        assertEquals(2, mints)
    }

    @Test
    fun aSharedRequestStaysUntilEveryHolderCancels() {
        val t = tickets()
        val a = t.request("site")
        t.request("site")
        t.cancel(a)
        assertEquals("", t.take(a))
        t.cancel(a)
        assertTrue(t.take(a).contains("unknown ticket"))
    }

    @Test
    fun heldTicketsAreCapped() {
        val t = tickets(TicketBudget(maxOutstanding = 2, burst = 10, now = { clock }))
        val a = t.request("a")
        t.request("b")
        assertEquals("", t.request("c"))
        t.cancel(a)
        assertNotEquals("", t.request("c"))
    }

    @Test
    fun mintingIsRateLimited() {
        val t = tickets(TicketBudget(maxOutstanding = 10, burst = 2, refillMs = 1000, now = { clock }))
        t.cancel(t.request("a"))
        t.cancel(t.request("b"))
        assertEquals("a third mint inside the burst window is refused", "", t.request("c"))
        clock += 1000
        assertNotEquals("", t.request("c"))
        assertEquals(3, mints)
    }

    @Test
    fun budgetRefillsOneTokenPerInterval() {
        val b = TicketBudget(maxOutstanding = 9, burst = 3, refillMs = 100, now = { clock })
        repeat(3) { assertTrue(b.tryTake(0)) }
        assertFalse(b.tryTake(0))
        clock += 250
        assertTrue(b.tryTake(0))
        assertTrue(b.tryTake(0))
        assertFalse(b.tryTake(0))
        clock += 10_000
        repeat(3) { assertTrue(b.tryTake(0)) }
        assertFalse("never more than the burst", b.tryTake(0))
        assertFalse("held tickets are capped first", TicketBudget(maxOutstanding = 1, now = { clock }).tryTake(1))
    }
}
