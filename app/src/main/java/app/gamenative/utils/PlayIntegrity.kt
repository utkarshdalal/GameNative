package app.gamenative.utils

import android.app.Application
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PlayIntegrity {

    private const val TAG = "PlayIntegrity"
    private const val TOKEN_WAIT_FOR_PROVIDER_MS = 3_000L
    private const val TOKEN_RETRY_DELAY_MS = 2_000L
    private const val BACKOFF_AFTER_QUOTA_MS = 60 * 60 * 1_000L
    private const val BACKOFF_AFTER_TRANSIENT_MS = 5 * 60 * 1_000L
    private val PREPARE_RETRY_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L)

    private val TRANSIENT_ERRORS = setOf(
        StandardIntegrityErrorCode.NETWORK_ERROR,
        StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
        StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
        StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
        StandardIntegrityErrorCode.INTERNAL_ERROR,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prepareMutex = Mutex()

    private var manager: StandardIntegrityManager? = null
    private var cloudProjectNumber = 0L

    @Volatile
    private var tokenProvider: StandardIntegrityTokenProvider? = null

    @Volatile
    private var inFlightPrepare: Deferred<StandardIntegrityTokenProvider?>? = null

    @Volatile
    private var nextPrepareAllowedAt = 0L

    @Volatile
    private var unavailableForProcess = false

    fun warmUp(application: Application) {
        val projectNumber = BuildConfig.CLOUD_PROJECT_NUMBER.toLongOrNull()
        if (projectNumber == null || projectNumber == 0L) {
            Timber.tag(TAG).e("Invalid CLOUD_PROJECT_NUMBER: '${BuildConfig.CLOUD_PROJECT_NUMBER}'")
            unavailableForProcess = true
            PrefManager.playIntegrityAvailable = false
            return
        }
        cloudProjectNumber = projectNumber
        manager = IntegrityManagerFactory.createStandard(application)
        startPrepare()
    }

    suspend fun requestToken(requestBodyBytes: ByteArray): String? {
        val provider = awaitProvider() ?: return null
        val hash = sha256Hex(requestBodyBytes)

        return try {
            requestFrom(provider, hash)
        } catch (e: StandardIntegrityException) {
            when {
                e.errorCode == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID -> {
                    Timber.tag(TAG).w("Token provider invalid, preparing a new one")
                    invalidate(provider)
                    val fresh = awaitProvider() ?: return null
                    runCatching { requestFrom(fresh, hash) }
                        .onFailure { Timber.tag(TAG).e(it, "Token request failed after re-prepare") }
                        .getOrNull()
                }
                e.errorCode in TRANSIENT_ERRORS && e.errorCode != StandardIntegrityErrorCode.TOO_MANY_REQUESTS -> {
                    Timber.tag(TAG).w("Transient token error ${e.errorCode}, retrying once")
                    delay(TOKEN_RETRY_DELAY_MS)
                    runCatching { requestFrom(provider, hash) }
                        .onFailure { Timber.tag(TAG).e(it, "Token request retry failed") }
                        .getOrNull()
                }
                else -> {
                    Timber.tag(TAG).e(e, "Integrity token request failed (${e.errorCode})")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error requesting integrity token")
            null
        }
    }

    private suspend fun awaitProvider(): StandardIntegrityTokenProvider? {
        tokenProvider?.let { return it }
        val prepare = startPrepare() ?: return null
        return withTimeoutOrNull(TOKEN_WAIT_FOR_PROVIDER_MS) { prepare.await() }
    }

    private fun startPrepare(): Deferred<StandardIntegrityTokenProvider?>? {
        if (unavailableForProcess || manager == null) return null
        if (System.currentTimeMillis() < nextPrepareAllowedAt) return null
        inFlightPrepare?.takeIf { it.isActive }?.let { return it }
        return scope.async {
            prepareMutex.withLock {
                tokenProvider ?: prepareWithRetry()
            }
        }.also { inFlightPrepare = it }
    }

    private suspend fun prepareWithRetry(): StandardIntegrityTokenProvider? {
        var lastCode = StandardIntegrityErrorCode.NO_ERROR
        for (attempt in 0..PREPARE_RETRY_DELAYS_MS.size) {
            try {
                val provider = prepare()
                tokenProvider = provider
                PrefManager.playIntegrityAvailable = true
                Timber.tag(TAG).d("Token provider ready (attempt ${attempt + 1})")
                return provider
            } catch (e: StandardIntegrityException) {
                lastCode = e.errorCode
                if (e.errorCode !in TRANSIENT_ERRORS) {
                    Timber.tag(TAG).e(e, "Prepare failed with non-retryable error ${e.errorCode}")
                    unavailableForProcess = true
                    PrefManager.playIntegrityAvailable = false
                    return null
                }
                Timber.tag(TAG).w("Prepare failed with ${e.errorCode} (attempt ${attempt + 1})")
            } catch (e: Exception) {
                lastCode = StandardIntegrityErrorCode.INTERNAL_ERROR
                Timber.tag(TAG).w(e, "Prepare failed (attempt ${attempt + 1})")
            }
            if (attempt < PREPARE_RETRY_DELAYS_MS.size) delay(PREPARE_RETRY_DELAYS_MS[attempt])
        }

        val backoff = if (lastCode == StandardIntegrityErrorCode.TOO_MANY_REQUESTS) BACKOFF_AFTER_QUOTA_MS else BACKOFF_AFTER_TRANSIENT_MS
        nextPrepareAllowedAt = System.currentTimeMillis() + backoff
        PrefManager.playIntegrityAvailable = false
        Timber.tag(TAG).e("Giving up on prepare (last error $lastCode), next attempt in ${backoff / 1000}s")
        return null
    }

    private fun invalidate(provider: StandardIntegrityTokenProvider) {
        if (tokenProvider === provider) tokenProvider = null
    }

    private suspend fun prepare(): StandardIntegrityTokenProvider {
        val mgr = checkNotNull(manager)
        return suspendCancellableCoroutine { cont ->
            mgr.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build(),
            ).addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private suspend fun requestFrom(provider: StandardIntegrityTokenProvider, hash: String): String =
        suspendCancellableCoroutine { cont ->
            provider.request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(hash)
                    .build(),
            ).addOnSuccessListener { cont.resume(it.token()) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
