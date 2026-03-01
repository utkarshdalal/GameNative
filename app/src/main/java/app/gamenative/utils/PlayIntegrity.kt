package app.gamenative.utils

import android.app.Application
import app.gamenative.BuildConfig
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.security.MessageDigest
import kotlin.coroutines.resume

object PlayIntegrity {

    @Volatile
    private var tokenProvider: StandardIntegrityTokenProvider? = null

    fun warmUp(application: Application) {
        val cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER.toLongOrNull()
        if (cloudProjectNumber == null || cloudProjectNumber == 0L) {
            Timber.tag("PlayIntegrity").e("Invalid CLOUD_PROJECT_NUMBER: '${BuildConfig.CLOUD_PROJECT_NUMBER}'")
            return
        }

        val manager: StandardIntegrityManager =
            IntegrityManagerFactory.createStandard(application)

        manager.prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build(),
        ).addOnSuccessListener { provider ->
            tokenProvider = provider
            Timber.tag("PlayIntegrity").d("Token provider ready")
        }.addOnFailureListener { e ->
            Timber.tag("PlayIntegrity").e(e, "Failed to prepare integrity token provider")
        }
    }

    /**
     * Returns a fresh, one-use integrity token bound to the SHA-256 hash of
     * [requestBodyBytes], or null if the provider is not ready or the request fails.
     */
    suspend fun requestToken(requestBodyBytes: ByteArray): String? {
        val provider = tokenProvider ?: return null

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(requestBodyBytes)
            .joinToString("") { "%02x".format(it) }

        return try {
            suspendCancellableCoroutine { cont ->
                provider.request(
                    StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(hash)
                        .build(),
                ).addOnSuccessListener { token ->
                    cont.resume(token.token())
                }.addOnFailureListener { e ->
                    Timber.tag("PlayIntegrity").e(e, "Integrity token request failed")
                    cont.resume(null)
                }
            }
        } catch (e: Exception) {
            Timber.tag("PlayIntegrity").e(e, "Unexpected error requesting integrity token")
            null
        }
    }
}
