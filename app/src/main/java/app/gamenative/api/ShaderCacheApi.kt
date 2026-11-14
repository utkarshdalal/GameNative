package app.gamenative.api

import app.gamenative.shadercache.ShaderCacheKey
import app.gamenative.shadercache.ShaderPool
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object ShaderCacheApi {

    /** A pool as served: [layer] is the engine and its version, as in a [ShaderPool]. */
    data class Pool(val layer: String, val version: Long, val url: String, val sha256: String, val size: Long, val entries: Long)

    data class PoolVersion(val version: Long, val size: Long, val entries: Long)

    data class UploadSlot(val key: String, val putUrl: String)

    data class UploadMeta(
        val sha256: String,
        val size: Long,
        val appVersion: String,
        val gpu: String,
        val driver: String,
        val displayServer: String,
    )

    /** The most games one [checkPools] request may ask about. */
    const val MAX_CHECK_GAMES = 500

    private const val TAG = "ShaderCacheApi"
    private const val BASE_URL = "https://api.gamenative.app/v1/shadercache"

    private data class Raw(val code: Int, val body: String)

    /** The pools of [key] the server has, by layer; empty when it has none. */
    suspend fun fetchPools(key: ShaderCacheKey): ApiResult<Map<String, Pool>> = call("pools") {
        val url = "$BASE_URL/pools".toHttpUrl().newBuilder()
            .addQueryParameter("game", key.game)
            .apply {
                key.dxvk?.let { v -> addQueryParameter("dxvk", v) }
                key.vkd3d?.let { v -> addQueryParameter("vkd3d", v) }
            }
            .build()
        it.url(url).get()
    }.map { raw -> if (raw.code == 404) emptyMap() else parsePools(raw.body) }

    internal fun parsePools(body: String): Map<String, Pool> {
        // Another service on this host can answer an unknown path with a 200 of its own.
        val pools = JSONObject(body).optJSONArray("pools") ?: throw IOException("pools: no pools in the response")
        return buildMap {
            for (i in 0 until pools.length()) {
                val pool = pools.getJSONObject(i)
                val layer = layer(pool) ?: continue
                put(
                    layer,
                    Pool(
                        layer = layer,
                        version = pool.getLong("version"),
                        url = pool.getString("url"),
                        sha256 = pool.getString("sha256").lowercase(),
                        size = pool.optLong("size", -1L),
                        entries = pool.optLong("entries", 0L),
                    ),
                )
            }
        }
    }

    /** The current version of each pool of [keys] (at most [MAX_CHECK_GAMES]) the server has; others are absent. */
    suspend fun checkPools(keys: List<ShaderCacheKey>): ApiResult<Map<ShaderPool, PoolVersion>> = call("pools/check") {
        it.url("$BASE_URL/pools/check").post(jsonBody(checkRequest(keys)))
    }.map { raw -> parseCheck(raw.body, keys) }

    internal fun checkRequest(keys: List<ShaderCacheKey>): JSONObject = JSONObject().put(
        "games",
        JSONArray(
            keys.map { key ->
                JSONObject().put("game", key.game).apply {
                    key.dxvk?.let { put("dxvk", it) }
                    key.vkd3d?.let { put("vkd3d", it) }
                }
            },
        ),
    )

    internal fun parseCheck(body: String, keys: List<ShaderCacheKey>): Map<ShaderPool, PoolVersion> {
        val subpools = JSONObject(body).optJSONObject("subpools") ?: throw IOException("pools/check: no subpools in the response")
        val asked = keys.flatMap { it.pools }.toSet()
        return buildMap {
            for (game in subpools.keys()) {
                val list = subpools.optJSONArray(game) ?: continue
                for (i in 0 until list.length()) {
                    val entry = list.optJSONObject(i) ?: continue
                    val pool = ShaderPool(game, layer(entry) ?: continue).takeIf { it in asked } ?: continue
                    put(pool, PoolVersion(entry.getLong("version"), entry.optLong("size", -1L), entry.optLong("entries", 0L)))
                }
            }
        }
    }

    private fun layer(json: JSONObject): String? {
        val engine = json.optString("engine")
        val version = json.optString("engineVersion")
        return if (engine.isEmpty() || version.isEmpty()) null else "$engine-$version"
    }

    suspend fun beginUpload(key: ShaderCacheKey, meta: UploadMeta): ApiResult<UploadSlot> {
        val body = JSONObject()
            .put("game", key.game)
            .put("sha256", meta.sha256)
            .put("size", meta.size)
            .put("appVersion", meta.appVersion)
            .put("gpu", meta.gpu)
            .put("driver", meta.driver)
            .put("displayServer", meta.displayServer)
        key.dxvk?.let { body.put("dxvk", it) }
        key.vkd3d?.let { body.put("vkd3d", it) }
        return call("upload/begin") { it.url("$BASE_URL/upload/begin").post(jsonBody(body)) }.map { raw ->
            val json = JSONObject(raw.body)
            UploadSlot(key = json.getString("key"), putUrl = json.getString("putUrl"))
        }
    }

    suspend fun finishUpload(uploadKey: String): ApiResult<Unit> =
        call("upload/done") {
            it.url("$BASE_URL/upload/done").post(jsonBody(JSONObject().put("key", uploadKey)))
        }.map { }

    private fun jsonBody(json: JSONObject) = json.toString().toRequestBody("application/json".toMediaType())

    private suspend fun call(name: String, build: (Request.Builder) -> Request.Builder): ApiResult<Raw> =
        withContext(Dispatchers.IO) {
            try {
                val token = AccountApi.currentAccessTokenOrNull()
                var raw = send(request(build, token))
                if (raw.code == 401 && token != null) raw = send(request(build, AccountApi.refreshedAccessTokenOrNull(token)))
                if (raw.code in 200..299 || (name == "pools" && raw.code == 404)) {
                    ApiResult.Success(raw)
                } else {
                    Timber.tag(TAG).w("%s HTTP %d", name, raw.code)
                    ApiResult.HttpError(raw.code, raw.body.take(200))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "%s failed", name)
                ApiResult.NetworkError(e)
            }
        }

    private fun request(build: (Request.Builder) -> Request.Builder, token: String?): Request =
        build(Request.Builder()).apply { if (token != null) header("Authorization", "Bearer $token") }.build()

    private suspend fun send(request: Request): Raw = suspendCancellableCoroutine { continuation ->
        val call = GameNativeApi.httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val raw = try {
                        response.use { Raw(it.code, it.body.string()) }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(raw)
                }
            },
        )
    }

    private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Success -> try {
            ApiResult.Success(transform(data))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Bad response")
            ApiResult.NetworkError(e)
        }
        is ApiResult.HttpError -> this
        is ApiResult.NetworkError -> this
    }
}
