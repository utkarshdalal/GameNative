package app.gamenative.optimize

import app.gamenative.api.GameNativeApi
import app.gamenative.utils.KeyAttestationHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Attestation gets [TIMEOUT_MS] per request; after a failure or timeout requests go without it for [SKIP_MS]. */
internal object AttestationBudget {
    const val TIMEOUT_MS = 2_000L
    const val SKIP_MS = 60L * 60 * 1000

    @Volatile
    private var failedAtMillis: Long? = null

    fun shouldTry(failedAtMillis: Long?, nowMillis: Long): Boolean = failedAtMillis == null || nowMillis - failedAtMillis !in 0 until SKIP_MS

    fun shouldTry(nowMillis: Long = System.currentTimeMillis()): Boolean = shouldTry(failedAtMillis, nowMillis)

    /** Records a failure; true when it starts a new skip window, so it is logged once per window. */
    fun failed(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val fresh = shouldTry(nowMillis)
        failedAtMillis = nowMillis
        return fresh
    }

    fun succeeded() {
        failedAtMillis = null
    }
}

sealed class NextRungResult {
    data class Answered(val response: NextRungResponse) : NextRungResult()

    /** The server did not take the account token: the user is signed out. */
    data object SignedOut : NextRungResult()

    data object Failed : NextRungResult()
}

object OptimizeApi {
    private const val ATTESTATION_BASE_URL = "https://api.gamenative.app"
    private const val NEXT_RUNG_PATH = "/api/optimize/next-rung"
    private const val OUTCOME_PATH = "/api/optimize/outcome"
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

    /** The nonce and chain within [AttestationBudget.TIMEOUT_MS]; a slow attempt keeps running off the request's path. */
    private suspend fun attestation(): Pair<String, List<String>>? {
        if (!AttestationBudget.shouldTry()) return null
        val attempt = scope.async { KeyAttestationHelper.getAttestationFields(ATTESTATION_BASE_URL) }
        val fields = withTimeoutOrNull(AttestationBudget.TIMEOUT_MS) { attempt.await() }
        if (fields != null) {
            AttestationBudget.succeeded()
            return fields
        }
        attempt.cancel()
        if (AttestationBudget.failed()) Timber.tag("Optimize").w("attestation unavailable, continuing without it")
        return null
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        body.remove("nonce")
        body.remove("attestationChain")
        attestation()?.let { (nonce, chain) ->
            body.put("nonce", nonce)
            body.put("attestationChain", JSONArray(chain))
        }
        val request = GameNativeApi.buildPostRequest(GameNativeApi.BASE_URL + path, body)
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
     * Starts a next-rung request, attestation included, that runs to its end even when the caller stops waiting.
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
