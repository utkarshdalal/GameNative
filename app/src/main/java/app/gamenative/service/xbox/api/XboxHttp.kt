package app.gamenative.service.xbox.api

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A non-2xx answer from an Xbox service. [body] is truncated. */
class XboxHttpException(val url: String, val code: Int, val body: String) :
    IOException("HTTP $code from ${url.substringBefore('?')}")

internal object XboxHttp {
    val JSON = "application/json".toMediaType()

    class Reply(val code: Int, val body: String, val headers: Map<String, String>)

    suspend fun send(client: OkHttpClient, request: Request, checkStatus: Boolean = true): Reply =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful && checkStatus) {
                    throw XboxHttpException(request.url.toString(), response.code, text.take(512))
                }
                Reply(response.code, text, response.headers.associate { (k, v) -> k.lowercase() to v })
            }
        }

    fun jsonBody(bytes: ByteArray) = bytes.toRequestBody(JSON)
}

/** A non-empty string value, or null when absent or JSON null (Android's optString returns "null" for those). */
internal fun org.json.JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else (opt(key) as? String)?.takeIf { it.isNotEmpty() }
