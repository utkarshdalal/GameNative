package app.gamenative.service

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_AddToWishlist_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_RemoveFromWishlist_Request
import `in`.dragonbra.javasteam.rpc.service.Wishlist
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

object SteamWishlistService {

    private const val TAG = "SteamWishlist"
    private const val JOB_TIMEOUT_MS = 15_000L
    private const val GET_URL = "https://api.steampowered.com/IWishlistService/GetWishlist/v1/"
    private const val STORE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    sealed interface Outcome {
        data object Success : Outcome
        data object NoSession : Outcome
        data class Failed(val result: EResult?) : Outcome
    }

    suspend fun addToWishlist(appId: Int): Outcome = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext Outcome.NoSession
        val request = CWishlist_AddToWishlist_Request.newBuilder().setAppid(appId).build()
        runJob("AddToWishlist") {
            service.addToWishlist(request).also { it.timeout = JOB_TIMEOUT_MS }.toFuture().await().result
        }
    }

    // UTM attribution requires the visit and the wishlist add to happen in the same web session.
    // A real WebView earns the bot-manager/browserid cookies that let Valve count the visit as
    // tracked, so it is the primary path; the bare-HTTP add and the CM add are fallbacks.
    suspend fun addToWishlistAttributed(context: Context, appId: Int, campaignId: String): Outcome =
        withContext(Dispatchers.IO) {
            val steamId = SteamService.userSteamId?.convertToUInt64()
            if (steamId != null && steamId != 0L) {
                var token = PrefManager.accessToken.ifEmpty { null } ?: refreshAccessToken()
                if (!token.isNullOrEmpty()) {
                    if (tryWebView(context, steamId, token, appId, campaignId)) return@withContext Outcome.Success
                    val fresh = refreshAccessToken()
                    if (!fresh.isNullOrEmpty() && fresh != token &&
                        tryWebView(context, steamId, fresh, appId, campaignId)
                    ) {
                        return@withContext Outcome.Success
                    }
                }
            }
            val webOk = try {
                webAttributedAdd(appId, campaignId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "attributed add failed")
                false
            }
            if (webOk) Outcome.Success else addToWishlist(appId)
        }

    private suspend fun tryWebView(context: Context, steamId: Long, token: String, appId: Int, campaignId: String): Boolean =
        try {
            WishlistWebViewAdder.add(context, steamId, token, appId, campaignId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "webview attributed add failed")
            false
        }

    private suspend fun webAttributedAdd(appId: Int, campaignId: String): Boolean {
        val steamId = SteamService.userSteamId?.convertToUInt64() ?: return false
        var token = PrefManager.accessToken
        repeat(2) { attempt ->
            if (token.isEmpty()) token = refreshAccessToken() ?: return false
            val cookie = "steamLoginSecure=$steamId%7C%7C${URLEncoder.encode(token, "UTF-8")}; " +
                "birthtime=0; lastagecheckage=1-January-1970; wantsmatureconctent=1"
            val utmUrl = "https://store.steampowered.com/app/$appId/" +
                "?utm_source=gamenative&utm_medium=app&utm_campaign=${URLEncoder.encode(campaignId, "UTF-8")}"
            var sessionId: String? = null
            var loggedIn = false
            Net.http.newCall(
                Request.Builder().url(utmUrl)
                    .header("User-Agent", STORE_UA)
                    .header("Cookie", cookie)
                    .build(),
            ).execute().use { res ->
                res.headers("Set-Cookie").forEach { c ->
                    if (c.startsWith("sessionid=")) sessionId = c.substringAfter("sessionid=").substringBefore(';')
                }
                val body = res.body?.string().orEmpty()
                loggedIn = Regex("data-userinfo=\"([^\"]*)\"").find(body)
                    ?.groupValues?.get(1)?.contains("&quot;logged_in&quot;:true") == true
                Timber.tag(TAG).i("utm visit http=${res.code} loggedIn=$loggedIn sessionid=${sessionId != null} campaign=$campaignId")
            }
            if (loggedIn && sessionId != null) {
                Net.http.newCall(
                    Request.Builder().url("https://store.steampowered.com/api/addtowishlist")
                        .header("User-Agent", STORE_UA)
                        .header("Cookie", "$cookie; sessionid=$sessionId")
                        .header("Referer", utmUrl)
                        .header("Origin", "https://store.steampowered.com")
                        .post(FormBody.Builder().add("appid", appId.toString()).add("sessionid", sessionId!!).build())
                        .build(),
                ).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    val ok = res.isSuccessful && JSONObject(body).optBoolean("success")
                    Timber.tag(TAG).i("web addtowishlist http=${res.code} body=$body -> $ok")
                    return ok
                }
            }
            Timber.tag(TAG).w("utm visit not logged in (attempt ${attempt + 1}), refreshing token")
            token = ""
        }
        return false
    }

    private fun steamIdFromToken(token: String): Long? = try {
        val payload = token.split(".")[1]
        val json = String(java.util.Base64.getUrlDecoder().decode(payload))
        JSONObject(json).optString("sub").toLongOrNull()
    } catch (e: Exception) {
        null
    }

    private suspend fun refreshAccessToken(): String? {
        val client = SteamService.instance?.steamClient ?: return null
        val steamId = client.steamID ?: return null
        val refresh = PrefManager.refreshToken.ifEmpty { return null }
        return try {
            val result = client.authentication.generateAccessTokenForApp(steamId, refresh, false).await()
            if (result.accessToken.isNotEmpty()) {
                PrefManager.accessToken = result.accessToken
                if (result.refreshToken.isNotEmpty()) PrefManager.refreshToken = result.refreshToken
                Timber.tag(TAG).i("refreshed store access token over CM")
                result.accessToken
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "access token refresh failed")
            null
        }
    }

    suspend fun removeFromWishlist(appId: Int): Outcome = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext Outcome.NoSession
        val request = CWishlist_RemoveFromWishlist_Request.newBuilder().setAppid(appId).build()
        runJob("RemoveFromWishlist") {
            service.removeFromWishlist(request).also { it.timeout = JOB_TIMEOUT_MS }.toFuture().await().result
        }
    }

    // The public steamid read returns nothing for private wishlists, so prefer the
    // token-authenticated form, which always sees the caller's own list.
    suspend fun isWishlisted(appId: Int): Boolean? = withContext(Dispatchers.IO) {
        readWishlist(PrefManager.accessToken.ifEmpty { null })?.let {
            return@withContext it.contains(appId)
        }
        val fresh = refreshAccessToken() ?: return@withContext null
        readWishlist(fresh)?.contains(appId)
    }

    private fun readWishlist(token: String?): Set<Int>? {
        val builder = GET_URL.toHttpUrl().newBuilder()
        if (token != null) {
            val steamId = steamIdFromToken(token) ?: SteamService.userSteamId?.convertToUInt64()
            if (steamId == null || steamId == 0L) {
                Timber.tag(TAG).w("cannot resolve steamid for authed wishlist read")
                return null
            }
            builder.addQueryParameter("access_token", token)
            builder.addQueryParameter("steamid", steamId.toString())
        } else {
            val steamId = SteamService.userSteamId?.convertToUInt64()
            if (steamId == null || steamId == 0L) {
                Timber.tag(TAG).w("no token or steam session, cannot read wishlist")
                return null
            }
            builder.addQueryParameter("steamid", steamId.toString())
        }
        return try {
            Net.http.newCall(Request.Builder().url(builder.build()).build()).execute().use { res ->
                if (!res.isSuccessful) {
                    Timber.tag(TAG).w("wishlist read failed ${res.code} (authed=${token != null})")
                    return@use null
                }
                val response = JSONObject(res.body?.string().orEmpty()).optJSONObject("response")
                // Authed: absent items = empty wishlist. Public: absent = private, i.e. unknown.
                val items = response?.optJSONArray("items")
                    ?: return@use if (token != null) emptySet() else null
                (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optInt("appid") }.toSet()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "wishlist read failed")
            null
        }
    }

    private fun service(): Wishlist? {
        val client = SteamService.instance?.steamClient
        if (client == null) {
            Timber.tag(TAG).w("no steam client")
            return null
        }
        val unifiedMessages = client.getHandler<SteamUnifiedMessages>()
        if (unifiedMessages == null) {
            Timber.tag(TAG).e("SteamUnifiedMessages handler not available")
            return null
        }
        return try {
            unifiedMessages.createService(Wishlist::class.java)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "cannot create Wishlist service")
            null
        }
    }

    private suspend fun runJob(method: String, block: suspend () -> EResult?): Outcome = try {
        val result = block()
        Timber.tag(TAG).i("$method -> $result")
        if (result == EResult.OK) Outcome.Success else Outcome.Failed(result)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "$method failed")
        Outcome.Failed(null)
    }
}
