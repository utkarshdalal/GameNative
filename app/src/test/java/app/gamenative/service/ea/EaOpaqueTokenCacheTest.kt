package app.gamenative.service.ea

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EaOpaqueTokenCacheTest {
    @Test
    fun `launch and SDK reuse the token until the refresh margin`() = runBlocking {
        var now = 1000L
        var calls = 0
        val cache = EaOpaqueTokenCache { now }
        suspend fun token() = cache.get("account-token") { "opaque-${++calls}" }
        assertEquals("opaque-1", token())
        now += 499_999
        assertEquals("opaque-1", token())
        now++
        assertEquals("opaque-2", token())
        assertEquals(2, calls)
    }

    @Test
    fun `account changes do not reuse another accounts token`() = runBlocking {
        val cache = EaOpaqueTokenCache { 0L }
        assertEquals("alice", cache.get("alice-parent") { "alice" })
        assertEquals("bob", cache.get("bob-parent") { "bob" })
    }

    @Test
    fun `failed exchange is retried instead of cached`() = runBlocking {
        val cache = EaOpaqueTokenCache { 0L }
        runCatching { cache.get("parent") { error("offline") } }
        assertEquals("opaque", cache.get("parent") { "opaque" })
    }
}
