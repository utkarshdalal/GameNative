package app.gamenative.service

import app.gamenative.PrefManager
import app.gamenative.steam.WishlistService
import app.gamenative.utils.Net
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.CAuthentication_AccessToken_GenerateForApp_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.ETokenRenewalType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_AddToWishlist_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_RemoveFromWishlist_Request
import `in`.dragonbra.javasteam.rpc.service.Authentication
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Wishlist add/remove over the logged-in JavaSteam session.
 *
 * Writes go out on the authenticated CM connection, so no web token is involved. The read still
 * uses the public web endpoint because it only needs a steamid; a wishlist set to private is
 * therefore unreadable and reports null rather than "not wishlisted".
 */
object SteamWishlistService {

    private const val TAG = "SteamWishlist"
    private const val JOB_TIMEOUT_MS = 15_000L
    private const val GET_URL = "https://api.steampowered.com/IWishlistService/GetWishlist/v1/"
    private const val ADD_URL = "https://api.steampowered.com/IWishlistService/AddToWishlist/v1/"

    sealed interface Outcome {
        data object Success : Outcome
        data object NoSession : Outcome
        data class Failed(val result: EResult?) : Outcome
    }

    suspend fun addToWishlist(appId: Int): Outcome = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext Outcome.NoSession
        val request = CWishlist_AddToWishlist_Request.newBuilder().setAppid(appId).build()
        val viaCm = runJob("AddToWishlist") {
            service.addToWishlist(request).also { it.timeout = JOB_TIMEOUT_MS }.toFuture().await().result
        }
        if (viaCm is Outcome.Success) return@withContext viaCm

        // The CM routes Wishlist but denies a plain client session, so retry the web endpoint with a
        // token minted from the session's refresh token.
        val token = mintWebToken() ?: return@withContext viaCm
        val body = FormBody.Builder().add("access_token", token).add("appid", appId.toString()).build()
        try {
            Net.http.newCall(Request.Builder().url(ADD_URL).post(body).build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                Timber.tag(TAG).i("AddToWishlist via minted token -> ${res.code} ${text.take(200)}")
                if (res.isSuccessful) Outcome.Success else viaCm
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "wishlist web write failed")
            viaCm
        }
    }

    /** Exchanges the session refresh token for an access token, over the CM connection. */
    private suspend fun mintWebToken(): String? {
        val client = SteamService.instance?.steamClient ?: return null
        val unifiedMessages = client.getHandler<SteamUnifiedMessages>() ?: return null
        val steamId = SteamService.userSteamId?.convertToUInt64() ?: return null
        val refresh = SteamService.sessionRefreshToken
            ?: PrefManager.refreshToken.ifEmpty { null }
        if (refresh == null) {
            Timber.tag(TAG).w("no refresh token available to mint with")
            return null
        }
        return try {
            val auth = unifiedMessages.createService(Authentication::class.java)
            val request = CAuthentication_AccessToken_GenerateForApp_Request.newBuilder()
                .setRefreshToken(refresh)
                .setSteamid(steamId)
                .setRenewalType(ETokenRenewalType.k_ETokenRenewalType_None)
                .build()
            val response = auth.generateAccessTokenForApp(request)
                .also { it.timeout = JOB_TIMEOUT_MS }
                .toFuture().await()
            if (response.result != EResult.OK) {
                Timber.tag(TAG).w("mint failed: ${response.result}")
                return null
            }
            response.body.build().accessToken.ifEmpty { null }
                .also { Timber.tag(TAG).i("minted token: ${it != null}") }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "mint failed")
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

    /** True/false when the wishlist could be read, null when it could not be determined. */
    suspend fun isWishlisted(appId: Int): Boolean? = withContext(Dispatchers.IO) {
        val steamId = SteamService.userSteamId?.convertToUInt64()
        if (steamId == null || steamId == 0L) {
            Timber.tag(TAG).w("no live steam session, cannot read wishlist")
            return@withContext null
        }
        val url = GET_URL.toHttpUrl().newBuilder()
            .addQueryParameter("steamid", steamId.toString())
            .build()
        try {
            Net.http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) {
                    Timber.tag(TAG).w("wishlist read failed ${res.code}")
                    return@use null
                }
                val response = JSONObject(res.body?.string().orEmpty()).optJSONObject("response")
                // Absent (rather than empty) items means private or unreadable, not "nothing wishlisted".
                val items = response?.optJSONArray("items") ?: return@use null
                (0 until items.length()).any { items.optJSONObject(it)?.optInt("appid") == appId }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "wishlist read failed")
            null
        }
    }

    private fun service(): WishlistService? {
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
        // Replies are routed by service name, so the service must be registered via createService.
        return try {
            unifiedMessages.createService(WishlistService::class.java)
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
