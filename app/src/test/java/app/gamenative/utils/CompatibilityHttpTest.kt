package app.gamenative.utils

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CompatibilityHttpTest {
    private val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()

    @Test fun slowErrorBodyCannotHideALongServerCooldown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(429).setHeader("Retry-After", "600")
                    .setBody("unused error page").setBodyDelay(2, TimeUnit.SECONDS),
            )
            val call = client.newCall(Request.Builder().url(server.url("/")).build())
            val result = withTimeoutOrNull(1_000L) { CompatibilityHttp.execute(call) }
            assertNotNull(result)
            assertEquals("600", result!!.retryAfter)
            assertEquals(429, result.code)
            assertNull(result.body)
        }
    }

    @Test fun successfulBodiesAreStillRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("success"))
            val result = CompatibilityHttp.execute(client.newCall(Request.Builder().url(server.url("/")).build()))
            assertTrue(result.isSuccessful)
            assertEquals("success", result.body)
        }
    }

    @Test fun timeoutCancelsAnHttpCallEvenAfterHeadersArrive() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("slow body").setBodyDelay(2, TimeUnit.SECONDS))
            val call = client.newCall(Request.Builder().url(server.url("/")).build())
            assertNull(withTimeoutOrNull(250L) { CompatibilityHttp.execute(call) })
            assertTrue(call.isCanceled())
        }
    }

    @Test fun callerCancellationCancelsTheCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("slow body").setBodyDelay(2, TimeUnit.SECONDS))
            val call = client.newCall(Request.Builder().url(server.url("/")).build())
            val fetch = async(Dispatchers.IO) { CompatibilityHttp.execute(call) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            fetch.cancelAndJoin()
            assertTrue(call.isCanceled())
        }
    }

    @Test fun bulkStatsSuccessAndFailureAreNotConfusedWithEmptySuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"games":{}}"""))
            server.enqueue(MockResponse().setBody("""{"error":"bad response"}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            val url = server.url("/").toString()
            assertEquals(emptyMap<Any, Any>(), DeviceGameStatsService.fetch(url, false, "test", client))
            assertNull(DeviceGameStatsService.fetch(url, false, "test", client))
            assertNull(DeviceGameStatsService.fetch(url, false, "test", client))
        }
    }

    @Test fun bulkStatsRetryWaitIsInsideTheTotalTimeBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            val error = try {
                DeviceGameStatsService.fetch(server.url("/").toString(), false, "test", client, timeoutMillis = 250L)
                fail("Expected retained server cooldown")
                error("unreachable")
            } catch (error: GameCompatibilityService.RateLimited) {
                error
            }
            assertTrue(error.retryAfterMillis >= 60_000L)
            assertEquals(1, server.requestCount)
        }
    }
}
