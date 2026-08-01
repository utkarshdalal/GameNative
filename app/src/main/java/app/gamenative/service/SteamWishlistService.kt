package app.gamenative.service

import app.gamenative.PrefManager
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Steam wishlist over the public IWishlistService endpoints.
 *
 * The stored session token comes from a client login, so it may not carry the audience the web
 * endpoints require. Writes therefore try the stored token first and fall back to minting a
 * web-audience token from the stored refresh token; [addToWishlist] reports which path succeeded
 * so the working route can be confirmed from logcat.
 */
object SteamWishlistService {

    private const val TAG = "SteamWishlist"

    private const val ADD_URL = "https://api.steampowered.com/IWishlistService/AddToWishlist/v1/"
    private const val REMOVE_URL = "https://api.steampowered.com/IWishlistService/RemoveFromWishlist/v1/"
    private const val GET_URL = "https://api.steampowered.com/IWishlistService/GetWishlist/v1/"
    private const val MINT_URL = "https://api.steampowered.com/IAuthenticationService/GenerateAccessTokenForApp/v1/"

    @Volatile private var mintedToken: String? = null

    sealed interface Outcome {
        data object Success : Outcome
        data object NoSession : Outcome
        data class Failed(val code: Int, val body: String) : Outcome
    }

    /** True/false when the wishlist could be read, null when it could not be determined. */
    suspend fun isWishlisted(appId: Int): Boolean? = withContext(Dispatchers.IO) {
        val steamId = PrefManager.steamUserSteamId64
        if (steamId == 0L) {
            Timber.tag(TAG).w("no steamId, cannot read wishlist")
            return@withContext null
        }
        val ids = readWishlist(steamId, PrefManager.accessToken.ifEmpty { null })
            ?: readWishlist(steamId, mintWebToken())
        if (ids == null) {
            Timber.tag(TAG).w("wishlist read failed for $steamId")
            return@withContext null
        }
        appId in ids
    }

    suspend fun addToWishlist(appId: Int): Outcome = write(ADD_URL, appId)

    suspend fun removeFromWishlist(appId: Int): Outcome = write(REMOVE_URL, appId)

    private suspend fun write(url: String, appId: Int): Outcome = withContext(Dispatchers.IO) {
        val stored = PrefManager.accessToken
        if (stored.isNotEmpty()) {
            when (val first = post(url, appId, stored)) {
                is Outcome.Success -> {
                    Timber.tag(TAG).i("$url ok with stored client token")
                    return@withContext first
                }
                is Outcome.Failed -> Timber.tag(TAG)
                    .w("stored client token rejected (${first.code}): ${first.body.take(200)}")
                else -> Unit
            }
        } else {
            Timber.tag(TAG).w("no stored access token")
        }

        val minted = mintWebToken() ?: return@withContext Outcome.NoSession
        val second = post(url, appId, minted)
        Timber.tag(TAG).i("$url with minted web token -> $second")
        second
    }

    private fun post(url: String, appId: Int, token: String): Outcome {
        val body = FormBody.Builder()
            .add("access_token", token)
            .add("appid", appId.toString())
            .build()
        return try {
            Net.http.newCall(Request.Builder().url(url).post(body).build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.isSuccessful) Outcome.Success else Outcome.Failed(res.code, text)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "wishlist write failed")
            Outcome.Failed(-1, e.message.orEmpty())
        }
    }

    /**
     * A public wishlist reads fine unauthenticated; a private one needs the token, and Steam only
     * accepts it as a query parameter here, so it lands in Steam's access logs.
     */
    private fun readWishlist(steamId: Long, token: String?): Set<Int>? {
        val url = GET_URL.toHttpUrl().newBuilder()
            .addQueryParameter("steamid", steamId.toString())
            .apply { token?.let { addQueryParameter("access_token", it) } }
            .build()
        val request = Request.Builder().url(url).build()
        return try {
            Net.http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                val response = JSONObject(res.body?.string().orEmpty()).optJSONObject("response")
                    ?: return null
                // Absent (rather than empty) items means private or unreadable, not "nothing wishlisted".
                val items = response.optJSONArray("items") ?: return null
                buildSet {
                    for (i in 0 until items.length()) {
                        items.optJSONObject(i)?.optInt("appid")?.let(::add)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "wishlist read failed")
            null
        }
    }

    /** Exchanges the stored refresh token for a web-audience access token. */
    private fun mintWebToken(): String? {
        mintedToken?.let { return it }
        val refresh = PrefManager.refreshToken
        val steamId = PrefManager.steamUserSteamId64
        if (refresh.isEmpty() || steamId == 0L) {
            Timber.tag(TAG).w("cannot mint web token: refresh=${refresh.isNotEmpty()} steamId=$steamId")
            return null
        }
        val body = FormBody.Builder()
            .add("steamid", steamId.toString())
            .add("refresh_token", refresh)
            .add("renewal_type", "0")
            .build()
        return try {
            Net.http.newCall(Request.Builder().url(MINT_URL).post(body).build()).execute().use { res ->
                if (!res.isSuccessful) {
                    Timber.tag(TAG).w("mint failed ${res.code}")
                    return null
                }
                val token = JSONObject(res.body?.string().orEmpty())
                    .optJSONObject("response")?.optString("access_token").orEmpty()
                if (token.isEmpty()) {
                    Timber.tag(TAG).w("mint returned no access_token")
                    null
                } else {
                    mintedToken = token
                    Timber.tag(TAG).i("minted web-audience token")
                    token
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "mint request failed")
            null
        }
    }
}
