package app.gamenative.service.ea

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** EA issues launch tokens for 550 seconds; reuse for at most 500 seconds. */
internal class EaOpaqueTokenCache(private val nowMillis: () -> Long) {
    private data class Entry(val parentToken: String, val token: String, val expiresAt: Long)
    private val mutex = Mutex()
    private var entry: Entry? = null

    suspend fun get(parentToken: String, fetch: suspend () -> String): String = mutex.withLock {
        val now = nowMillis()
        entry?.takeIf { it.parentToken == parentToken && now < it.expiresAt }?.let { return@withLock it.token }
        val token = fetch().also { check(it.isNotBlank()) { "EA returned an empty opaque token" } }
        entry = Entry(parentToken, token, now + 500_000)
        token
    }
}
