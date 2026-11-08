package app.gamenative.optimize

import app.gamenative.api.GameNativeApi
import app.gamenative.utils.KeyAttestationHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object OptimizeApi {
    private const val ATTESTATION_BASE_URL = "https://api.gamenative.app"
    private const val NEXT_RUNG_PATH = "/api/optimize/next-rung"
    private const val OUTCOME_PATH = "/api/optimize/outcome"
    private const val CALL_TIMEOUT_SEC = 15L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client by lazy {
        GameNativeApi.httpClient.newBuilder().callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS).build()
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        KeyAttestationHelper.getAttestationFields(ATTESTATION_BASE_URL)?.let { (nonce, chain) ->
            body.put("nonce", nonce)
            body.put("attestationChain", JSONArray(chain))
        }
        val request = GameNativeApi.buildPostRequest(GameNativeApi.BASE_URL + path, body)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag("Optimize").w("POST $path failed: HTTP ${response.code}")
                return@withContext null
            }
            val text = response.body?.string().orEmpty()
            if (text.isEmpty()) null else JSONObject(text)
        }
    }

    /** Returns null when the server did not answer within [timeoutMs]; the request is left to finish on its own. */
    suspend fun nextRung(body: JSONObject, timeoutMs: Long): NextRungResponse? {
        val call = scope.async {
            try {
                post(NEXT_RUNG_PATH, body)?.let { NextRungResponse.parse(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("Optimize").w(e, "next-rung failed")
                null
            }
        }
        return withTimeoutOrNull(timeoutMs) { call.await() }
    }

    /** One attempt and one retry; false when neither was acknowledged. */
    suspend fun postOutcome(body: JSONObject): Boolean {
        repeat(2) {
            try {
                if (post(OUTCOME_PATH, body)?.optBoolean("ok", false) == true) return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("Optimize").w(e, "outcome report failed")
            }
        }
        return false
    }
}
