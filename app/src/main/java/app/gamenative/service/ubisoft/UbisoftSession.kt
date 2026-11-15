package app.gamenative.service.ubisoft

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class UbisoftCredentials(
    val ticket: String,
    val sessionId: String,
    /** Local-clock time the ticket stops working, corrected by the server's own clock. */
    val expiresAt: Long,
    val userId: String,
    val profileId: String,
    val nameOnPlatform: String,
    val email: String = "",
    val country: String = "",
    /** The long-lived credential. It stays on Android and never goes into the prefix. */
    val rememberMeTicket: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ticket", ticket)
        .put("session_id", sessionId)
        .put("expires_at", expiresAt)
        .put("user_id", userId)
        .put("profile_id", profileId)
        .put("name_on_platform", nameOnPlatform)
        .put("email", email)
        .put("country", country)
        .put("remember_me_ticket", rememberMeTicket)

    companion object {
        fun fromJson(json: JSONObject) = UbisoftCredentials(
            ticket = json.getString("ticket"),
            sessionId = json.optString("session_id"),
            expiresAt = json.getLong("expires_at"),
            userId = json.optString("user_id"),
            profileId = json.optString("profile_id"),
            nameOnPlatform = json.optString("name_on_platform"),
            email = json.optString("email"),
            country = json.optString("country"),
            rememberMeTicket = json.optString("remember_me_ticket"),
        )

        /**
         * A session answer from the account service. The lifetime is taken as the server's
         * expiration minus the server's own time, added to the local clock, so a wrong device clock
         * does not shorten or stretch it. [previous] supplies what a renewal does not repeat.
         */
        fun fromSessionResponse(json: JSONObject, now: Long, previous: UbisoftCredentials? = null): UbisoftCredentials {
            val ticket = json.optString("ticket")
            require(ticket.isNotEmpty()) { "the session answer carries no ticket" }
            return UbisoftCredentials(
                ticket = ticket,
                sessionId = json.optString("sessionId").ifEmpty { previous?.sessionId.orEmpty() },
                expiresAt = expiresAt(now, json.optString("expiration"), json.optString("serverTime")),
                userId = json.optString("userId").ifEmpty { previous?.userId.orEmpty() },
                profileId = json.optString("profileId").ifEmpty { previous?.profileId.orEmpty() },
                nameOnPlatform = json.optString("nameOnPlatform").ifEmpty { previous?.nameOnPlatform.orEmpty() },
                email = previous?.email.orEmpty(),
                country = previous?.country.orEmpty(),
                rememberMeTicket = json.optString("rememberMeTicket").ifEmpty { previous?.rememberMeTicket.orEmpty() },
            )
        }

        internal const val FALLBACK_LIFETIME_MS = 60 * 60 * 1000L

        internal fun expiresAt(now: Long, expiration: String?, serverTime: String?): Long {
            val end = parseTime(expiration) ?: return now + FALLBACK_LIFETIME_MS
            val server = parseTime(serverTime) ?: return end
            return now + (end - server).coerceAtLeast(0L)
        }

        private fun parseTime(value: String?): Long? =
            value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}

/** What Ubisoft's sign-in page leaves in its storage once the user is signed in. */
data class UbisoftWebLogin(
    val ticket: String,
    val sessionId: String,
    val rememberMeTicket: String,
    val userId: String,
    val nameOnPlatform: String,
) {
    companion object {
        /**
         * [storage] is a JSON array of every storage value that parsed as a JSON object. The entry
         * with a ticket and a session id is the login; a remember-me ticket may sit in any entry.
         */
        fun fromStorage(storage: String): UbisoftWebLogin? {
            val array = runCatching { JSONArray(storage) }.getOrNull() ?: return null
            val objects = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            val login = objects.firstOrNull { it.optString("ticket").isNotEmpty() && it.optString("sessionId").isNotEmpty() }
                ?: return null
            val rememberMe = objects.map { it.optString("rememberMeTicket") }.firstOrNull { it.isNotEmpty() }.orEmpty()
            return UbisoftWebLogin(
                ticket = login.optString("ticket"),
                sessionId = login.optString("sessionId"),
                rememberMeTicket = rememberMe,
                userId = login.optString("userId").ifEmpty { login.optString("profileId") },
                nameOnPlatform = login.optString("nameOnPlatform"),
            )
        }
    }
}

/**
 * The launch-time freshness rule. Only an explicit rejection signs the user out; a network failure
 * never blocks a launch whose ticket is still valid.
 */
object UbisoftSessionPolicy {
    enum class Step { EXTEND, RENEW }
    enum class Verdict { USE_EXISTING, SIGN_IN_AGAIN, UNAVAILABLE }

    fun isFresh(now: Long, creds: UbisoftCredentials) = creds.expiresAt - now >= UbisoftConstants.MIN_TICKET_LIFE_MS

    /** What to try, in order, before a launch. Empty when the ticket is fresh enough as it is. */
    fun steps(now: Long, creds: UbisoftCredentials): List<Step> = when {
        isFresh(now, creds) -> emptyList()
        creds.expiresAt > now -> listOfNotNull(Step.EXTEND, Step.RENEW.takeIf { creds.rememberMeTicket.isNotEmpty() })
        creds.rememberMeTicket.isNotEmpty() -> listOf(Step.RENEW)
        else -> emptyList()
    }

    /** Called when every step failed. */
    fun afterFailedRefresh(
        now: Long,
        creds: UbisoftCredentials,
        extendRejected: Boolean,
        renewRejected: Boolean,
    ): Verdict {
        val hasRememberMe = creds.rememberMeTicket.isNotEmpty()
        return when {
            renewRejected || (extendRejected && !hasRememberMe) -> Verdict.SIGN_IN_AGAIN
            extendRejected -> Verdict.UNAVAILABLE
            creds.expiresAt > now -> Verdict.USE_EXISTING
            !hasRememberMe -> Verdict.SIGN_IN_AGAIN
            else -> Verdict.UNAVAILABLE
        }
    }
}
