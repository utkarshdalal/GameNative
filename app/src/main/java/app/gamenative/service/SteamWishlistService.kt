package app.gamenative.service

import app.gamenative.utils.Net
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_AddToWishlist_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_RemoveFromWishlist_Request
import `in`.dragonbra.javasteam.rpc.service.Wishlist
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

object SteamWishlistService {

    private const val TAG = "SteamWishlist"
    private const val JOB_TIMEOUT_MS = 15_000L
    private const val GET_URL = "https://api.steampowered.com/IWishlistService/GetWishlist/v1/"

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

    suspend fun removeFromWishlist(appId: Int): Outcome = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext Outcome.NoSession
        val request = CWishlist_RemoveFromWishlist_Request.newBuilder().setAppid(appId).build()
        runJob("RemoveFromWishlist") {
            service.removeFromWishlist(request).also { it.timeout = JOB_TIMEOUT_MS }.toFuture().await().result
        }
    }

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
                val items = response?.optJSONArray("items") ?: return@use null
                (0 until items.length()).any { items.optJSONObject(it)?.optInt("appid") == appId }
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
