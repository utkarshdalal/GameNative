// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** The PC package family names on Game Pass, with the ETag to send back as If-None-Match. */
data class GamePassPfns(val packageFamilyNames: List<String>, val etag: String?)

/**
 * catalog.gamepass.com reads (no sign-in). The subscriptions document is returned as-is: its
 * layout is not described anywhere this code is based on, so nothing is read out of it here.
 */
class GamePassCatalog(private val client: OkHttpClient, private val config: XboxConfig) {
    /** Store ids of the Game Pass subscription products. */
    val subscriptionIds: List<String> get() = config.list(XboxApiConfig.GAME_PASS_SUBSCRIPTION_IDS)

    /** The subscriptions document as returned. */
    suspend fun subscriptions(market: String, language: String): String {
        val url = config.fill(XboxApiConfig.GAME_PASS_SUBSCRIPTIONS_URL, mapOf("market" to market, "language" to language))
        return XboxHttp.send(client, Request.Builder().url(url).get().build()).body
    }

    /** Null when [etag] still matches (HTTP 304). */
    suspend fun pcPackageFamilyNames(etag: String? = null): GamePassPfns? {
        val request = Request.Builder()
            .url(config.value(XboxApiConfig.GAME_PASS_PFNS_URL))
            .apply { if (etag != null) header("If-None-Match", etag) }
            .get()
            .build()
        val reply = XboxHttp.send(client, request, checkStatus = false)
        if (reply.code == 304) return null
        if (reply.code !in 200..299) throw XboxHttpException(request.url.toString(), reply.code, reply.body.take(512))
        return GamePassPfns(parsePfns(reply.body), reply.headers["etag"])
    }

    companion object {
        fun parsePfns(json: String): List<String> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf { s -> s.isNotEmpty() } }
        }
    }
}
