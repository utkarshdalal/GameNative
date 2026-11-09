package app.gamenative.optimize

import app.gamenative.api.AccountApi
import app.gamenative.api.GameNativeApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

sealed class NextRungResult {
    data class Answered(val response: NextRungResponse) : NextRungResult()

    /** The server did not take the account token: the user is signed out. */
    data object SignedOut : NextRungResult()

    data object Failed : NextRungResult()
}

object OptimizeApi {
    private const val NEXT_RUNG_PATH = "/api/optimize/next-rung"
    private const val OUTCOME_PATH = "/api/optimize/outcome"
    private const val EVENTS_PATH = "/api/optimize/events"
    private const val CALL_TIMEOUT_SEC = 15L
    private const val CONNECT_TIMEOUT_SEC = 4L
    private const val MIN_RETRY_MS = 2_000L

    private class Unauthorized : Exception("HTTP 401")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client by lazy {
        GameNativeApi.httpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    /**
     * A plain JSON POST with the account's bearer token, which is the auth for Optimize. No key attestation or Play
     * Integrity token: both can take seconds, and the next-rung budget at launch is 10 s.
     */
    private suspend fun request(path: String, body: JSONObject): Request {
        val builder = Request.Builder()
            .url(GameNativeApi.BASE_URL + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
        AccountApi.currentAccessTokenOrNull()?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val request = request(path, body)
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw Unauthorized()
            if (!response.isSuccessful) {
                Timber.tag("Optimize").w("POST $path failed: HTTP ${response.code}")
                return@withContext null
            }
            val text = response.body?.string().orEmpty()
            if (text.isEmpty()) null else JSONObject(text)
        }
    }

    /**
     * Starts a next-rung request that runs to its end even when the caller stops waiting.
     * A failed attempt is tried once more while at least [MIN_RETRY_MS] of [budgetMs] is left.
     */
    fun nextRung(body: JSONObject, budgetMs: Long): Deferred<NextRungResult> {
        val startMs = System.currentTimeMillis()
        return scope.async {
            var result: NextRungResult = NextRungResult.Failed
            for (attempt in 0..1) {
                if (attempt > 0 && budgetMs - (System.currentTimeMillis() - startMs) < MIN_RETRY_MS) break
                result = try {
                    post(NEXT_RUNG_PATH, body)?.let { NextRungResult.Answered(NextRungResponse.parse(it)) } ?: NextRungResult.Failed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Unauthorized) {
                    NextRungResult.SignedOut
                } catch (e: Exception) {
                    Timber.tag("Optimize").w(e, "next-rung attempt ${attempt + 1} failed")
                    NextRungResult.Failed
                }
                if (result != NextRungResult.Failed) break
            }
            result
        }
    }

    /** One attempt; true when the server took the batch (it answers 204). */
    suspend fun postEvents(body: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(request(EVENTS_PATH, body)).execute().use { response ->
                if (!response.isSuccessful) Timber.tag("Optimize").w("POST $EVENTS_PATH failed: HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("Optimize").w(e, "optimize events failed")
            false
        }
    }

    /** One attempt and one retry; null when neither was acknowledged. */
    suspend fun postOutcome(body: JSONObject): OutcomeAck? {
        repeat(2) {
            try {
                OutcomeAck.parse(post(OUTCOME_PATH, body)).takeIf { it.ok }?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("Optimize").w(e, "outcome report failed")
            }
        }
        return null
    }
}
