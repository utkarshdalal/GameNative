package app.gamenative.api

import app.gamenative.BuildConfig
import app.gamenative.utils.Net
import app.gamenative.utils.PlayIntegrity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

object GameNativeApi {

    val BASE_URL: String
        get() = if (BuildConfig.DEBUG) (debugBaseUrlOverride() ?: "http://10.0.2.2:8787") else "https://api.gamenative.app"

    // Debug builds default to the emulator's host loopback, which a physical device cannot reach.
    // `adb shell setprop debug.gamenative.api_base https://api.gamenative.app` points one at a real server.
    private fun debugBaseUrlOverride(): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, "debug.gamenative.api_base") as? String
    }.getOrNull()?.trim()?.takeIf { it.toHttpUrlOrNull() != null }?.trimEnd('/')

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
        addAccountAuthorization(builder)

        return builder.build()
    }

    suspend fun buildGetRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
        addAccountAuthorization(builder)
        return builder.build()
    }

    private suspend fun addAccountAuthorization(builder: Request.Builder) {
        val accessToken = AccountApi.currentAccessTokenOrNull() ?: return
        builder.header("Authorization", "Bearer $accessToken")
    }
}
