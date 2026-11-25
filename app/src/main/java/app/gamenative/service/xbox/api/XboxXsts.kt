// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.api

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** An Xbox Live token response (XASD, XASU, XAST or XSTS). */
data class XboxToken(
    val token: String,
    val notAfter: Instant,
    val issueInstant: Instant?,
    val displayClaims: JSONObject?,
) {
    /** The xui uhs claim, present on user and XSTS tokens. */
    val userHash: String?
        get() = displayClaims?.optJSONArray("xui")?.optJSONObject(0)?.str("uhs")

    val xuid: String?
        get() = displayClaims?.optJSONArray("xui")?.optJSONObject(0)?.str("xid")

    val gamertag: String?
        get() = displayClaims?.optJSONArray("xui")?.optJSONObject(0)?.str("gtg")

    /** `XBL3.0 x={uhs};{token}`, the Authorization value Xbox services take. */
    fun authorizationHeader(): String {
        val uhs = userHash ?: throw IllegalStateException("XSTS response missing xui claim")
        return "XBL3.0 x=$uhs;$token"
    }

    fun isValid(now: Instant, margin: Duration = Duration.ofMinutes(5)): Boolean = notAfter.minus(margin).isAfter(now)

    companion object {
        fun parse(json: JSONObject): XboxToken = XboxToken(
            token = json.getString("Token"),
            notAfter = Instant.parse(json.getString("NotAfter")),
            issueInstant = json.str("IssueInstant")?.let(Instant::parse),
            displayClaims = json.optJSONObject("DisplayClaims"),
        )

        fun parse(json: String): XboxToken = parse(JSONObject(json))
    }
}

/**
 * XSTS tokens per relying party, the way xodus authorizes packagespc: the compact RPS ticket for
 * user.auth.xboxlive.com (MBI_SSL) from the Windows Live token exchange becomes a user token at
 * user/authenticate, which xsts/authorize turns into a token for the relying party. Neither call is signed.
 * Tokens are cached in memory until shortly before NotAfter.
 *
 * [userTicket] supplies that RPS ticket; it runs again whenever the user token has to be renewed.
 */
class XboxXsts(
    private val client: OkHttpClient,
    private val config: XboxConfig,
    private val userTicket: suspend () -> String,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Mutex()
    private var userToken: XboxToken? = null
    private val xsts = HashMap<String, XboxToken>()

    suspend fun token(relyingParty: String): XboxToken = lock.withLock {
        xsts[relyingParty]?.takeIf { it.isValid(clock()) }?.let { return it }
        val user = userToken?.takeIf { it.isValid(clock()) } ?: authenticateUser(userTicket()).also { userToken = it }
        requestXsts(user.token, relyingParty).also { xsts[relyingParty] = it }
    }

    suspend fun authorizationHeader(relyingParty: String): String = token(relyingParty).authorizationHeader()

    suspend fun clear() = lock.withLock {
        userToken = null
        xsts.clear()
    }

    private suspend fun authenticateUser(rpsTicket: String): XboxToken {
        val body = JSONObject()
            .put("RelyingParty", config.value(XboxApiConfig.AUTH_RELYING_PARTY))
            .put("TokenType", "JWT")
            .put(
                "Properties",
                JSONObject()
                    .put("AuthMethod", "RPS")
                    .put("SiteName", config.value(XboxApiConfig.USER_AUTH_SITE_NAME))
                    .put("RpsTicket", rpsTicket),
            )
        return post(config.value(XboxApiConfig.USER_AUTH_URL), body)
    }

    private suspend fun requestXsts(userToken: String, relyingParty: String): XboxToken {
        val body = JSONObject()
            .put("RelyingParty", relyingParty)
            .put("TokenType", "JWT")
            .put(
                "Properties",
                JSONObject()
                    .put("UserTokens", JSONArray().put(userToken))
                    .put("SandboxId", config.value(XboxApiConfig.SANDBOX)),
            )
        return post(config.value(XboxApiConfig.XSTS_URL), body)
    }

    private suspend fun post(url: String, body: JSONObject): XboxToken {
        val request = Request.Builder()
            .url(url)
            .header("x-xbl-contract-version", config.value(XboxApiConfig.AUTH_CONTRACT_VERSION))
            .post(XboxHttp.jsonBody(body.toString().toByteArray(Charsets.UTF_8)))
            .build()
        return XboxToken.parse(XboxHttp.send(client, request).body)
    }
}
