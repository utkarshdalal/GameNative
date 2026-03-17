package app.gamenative.api

import app.gamenative.BuildConfig
import app.gamenative.utils.Net
import app.gamenative.utils.PlayIntegrity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

object GameNativeApi {

    val BASE_URL: String =
        if (BuildConfig.DEBUG) "http://10.0.2.2:8787" else "https://api.gamenative.app"

    val httpClient: OkHttpClient = Net.http

    inline fun <T> executeRequest(request: Request, parser: (String) -> T): ApiResult<T> {
        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val message = try {
                    val json = JSONObject(body)
                    json.optJSONObject("error")?.optString("message") ?: body
                } catch (_: Exception) {
                    body
                }
                Timber.tag("GameNativeApi").w("HTTP ${response.code}: $message")
                return ApiResult.HttpError(response.code, message)
            }

            ApiResult.Success(parser(body))
        } catch (e: IOException) {
            Timber.tag("GameNativeApi").e(e, "Network error: ${e.message}")
            ApiResult.NetworkError(e)
        } catch (e: Exception) {
            Timber.tag("GameNativeApi").e(e, "Unexpected error: ${e.message}")
            ApiResult.NetworkError(e)
        }
    }

    suspend fun buildPostRequest(url: String, body: JSONObject): Request {
        val mediaType = "application/json".toMediaType()
        val bodyString = body.toString()
        val requestBody = bodyString.toRequestBody(mediaType)

        val integrityToken = PlayIntegrity.requestToken(bodyString.toByteArray())

        val builder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")

        if (integrityToken != null) {
            builder.header("X-Integrity-Token", integrityToken)
        }

        return builder.build()
    }

    fun buildGetRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .get()
            .build()
    }
}
