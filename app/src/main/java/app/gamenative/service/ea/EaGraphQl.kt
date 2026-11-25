package app.gamenative.service.ea

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/** EA's service aggregation layer, called the way the EA app calls it (full query, persisted-query hash). */
internal object EaGraphQl {
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** The response's `data` object; fails on HTTP errors and when GraphQL returns errors without data. */
    fun post(
        token: String,
        operation: String,
        query: String,
        variables: JSONObject,
        endpoint: String = EaConstants.SERVICE_AGGREGATION_ENDPOINT,
    ): JSONObject {
        val body = JSONObject()
            .put("operationName", operation)
            .put("query", query)
            .put("variables", variables)
            .put(
                "extensions",
                JSONObject().put(
                    "persistedQuery",
                    JSONObject().put("version", 1).put("sha256Hash", EaCrypto.hex(EaCrypto.sha256(query.toByteArray()))),
                ),
            )
        val req = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "EADesktop/13.778.0")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("EA $operation HTTP ${resp.code}: ${text.take(300)}")
            val json = JSONObject(text)
            val firstError = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            val data = json.optJSONObject("data") ?: error("EA $operation: ${firstError ?: "no data"} (${text.take(300)})")
            if (firstError != null) Timber.w("EA $operation returned errors with data: ${firstError.take(200)}")
            data
        }
    }
}
