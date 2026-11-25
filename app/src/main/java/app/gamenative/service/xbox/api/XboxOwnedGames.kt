// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The account's PC games as the Xbox app lists them (pcgafd "mygames"). The request carries the user's
 * compact MSA token in Authorization (`t=`) and an XSTS token in x-ms-authorization-social. Which MSA scope
 * and XSTS relying party the service accepts, and the field names of its answer, come from the helper
 * archive, so they can change without an app release; Store ids are collected from every field listed there.
 */
class XboxOwnedGames(
    private val client: OkHttpClient,
    private val config: XboxConfig,
    private val xsts: XboxXsts,
    private val msaToken: suspend () -> String,
) {
    suspend fun storeIds(market: String, language: String): List<String> {
        val url = config.fill(KEY_URL, mapOf("market" to market, "language" to language))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "t=${msaToken()}")
            .header("x-ms-authorization-social", xsts.authorizationHeader(config.value(KEY_RELYING_PARTY)))
            .get()
            .build()
        val body = XboxHttp.send(client, request).body
        return collect(body, config.list(KEY_ID_FIELDS).map { it.lowercase() }.toSet())
    }

    companion object {
        const val KEY_URL = "xbox.api.myGamesUrl"
        const val KEY_RELYING_PARTY = "xbox.api.myGamesRelyingParty"
        const val KEY_MSA_SCOPE = "xbox.api.myGamesMsaScope"
        const val KEY_MSA_POLICY = "xbox.api.myGamesMsaPolicy"
        const val KEY_ID_FIELDS = "xbox.api.myGamesIdFields"

        private val STORE_ID = Regex("^[0-9A-Z]{12}$")

        fun collect(body: String, fields: Set<String>): List<String> {
            val out = LinkedHashSet<String>()
            fun walk(node: Any?) {
                when (node) {
                    is JSONObject -> node.keys().forEach { key ->
                        val value = node.opt(key)
                        if (key.lowercase() in fields && value is String && STORE_ID.matches(value.uppercase())) out += value.uppercase()
                        walk(value)
                    }
                    is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
                }
            }
            val trimmed = body.trim()
            walk(if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed))
            return out.toList()
        }
    }
}
