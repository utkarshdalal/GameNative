package app.gamenative.api

import app.gamenative.shadercache.ShaderCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

object ShaderCacheApi {

    data class Pool(val version: Long, val url: String, val sha256: String, val size: Long, val entries: Long)

    data class UploadSlot(val key: String, val putUrl: String)

    data class UploadMeta(
        val sha256: String,
        val size: Long,
        val appVersion: String,
        val gpu: String,
        val driver: String,
        val displayServer: String,
    )

    private const val TAG = "ShaderCacheApi"
    private const val BASE_URL = "https://api.gamenative.app/v1/shadercache"

    private data class Raw(val code: Int, val body: String)

    suspend fun fetchPool(key: ShaderCacheKey): ApiResult<Pool?> = call("pool") {
        val url = "$BASE_URL/pool".toHttpUrl().newBuilder()
            .addQueryParameter("game", key.game)
            .addQueryParameter("dxvk", key.dxvk)
            .addQueryParameter("vkd3d", key.vkd3d)
            .build()
        it.url(url).get()
    }.map { raw ->
        if (raw.code == 404) return@map null
        val json = JSONObject(raw.body)
        Pool(
            version = json.getLong("version"),
            url = json.getString("url"),
            sha256 = json.getString("sha256").lowercase(),
            size = json.optLong("size", -1L),
            entries = json.optLong("entries", 0L),
        )
    }

    suspend fun beginUpload(key: ShaderCacheKey, meta: UploadMeta): ApiResult<UploadSlot> {
        val body = JSONObject()
            .put("game", key.game)
            .put("dxvk", key.dxvk)
            .put("vkd3d", key.vkd3d)
            .put("sha256", meta.sha256)
            .put("size", meta.size)
            .put("appVersion", meta.appVersion)
            .put("gpu", meta.gpu)
            .put("driver", meta.driver)
            .put("displayServer", meta.displayServer)
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
                val raw = AccountApi.sendAuthorized(build) { Raw(it.code, it.body.string()) }
                    ?: GameNativeApi.httpClient.newCall(build(Request.Builder()).build()).execute()
                        .use { Raw(it.code, it.body.string()) }
                if (raw.code in 200..299 || (name == "pool" && raw.code == 404)) {
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
