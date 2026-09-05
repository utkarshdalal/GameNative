package app.gamenative.mods

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

internal data class NexusStoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtEpochSeconds: Long,
    val account: NexusOAuthAccount? = null,
) {
    override fun toString(): String =
        "NexusStoredTokens(accessToken=[REDACTED], refreshToken=[REDACTED], " +
            "expiresAt=$accessTokenExpiresAtEpochSeconds, account=$account)"
}

internal data class NexusAuthorizationTransaction(
    val state: String,
    val codeVerifier: String,
    val createdAtEpochMillis: Long,
) {
    override fun toString(): String =
        "NexusAuthorizationTransaction(state=[REDACTED], codeVerifier=[REDACTED], createdAt=$createdAtEpochMillis)"
}

internal interface NexusOAuthStore {
    fun readTokens(): NexusStoredTokens?

    fun writeTokens(tokens: NexusStoredTokens)

    fun clearTokens()

    fun readTransaction(): NexusAuthorizationTransaction?

    fun writeTransaction(transaction: NexusAuthorizationTransaction)

    fun clearTransaction()
}

/**
 * Stores each OAuth record as one authenticated ciphertext, so access/refresh token rotation is
 * committed atomically. The Android Keystore key is non-exportable and dedicated to Nexus OAuth.
 */
internal class AndroidNexusOAuthStore(context: Context) : NexusOAuthStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val crypto = NexusOAuthAesGcm()
    private val lock = Any()

    override fun readTokens(): NexusStoredTokens? = synchronized(lock) {
        readEncrypted(TOKENS_KEY)?.let(::tokensFromJson)
    }

    override fun writeTokens(tokens: NexusStoredTokens) = synchronized(lock) {
        writeEncrypted(TOKENS_KEY, tokensToJson(tokens))
    }

    override fun clearTokens() = synchronized(lock) {
        if (!preferences.edit().remove(TOKENS_KEY).commit()) {
            throw IllegalStateException("Unable to clear Nexus credentials")
        }
    }

    override fun readTransaction(): NexusAuthorizationTransaction? = synchronized(lock) {
        readEncrypted(TRANSACTION_KEY)?.let(::transactionFromJson)
    }

    override fun writeTransaction(transaction: NexusAuthorizationTransaction) = synchronized(lock) {
        writeEncrypted(TRANSACTION_KEY, transactionToJson(transaction))
    }

    override fun clearTransaction() = synchronized(lock) {
        if (!preferences.edit().remove(TRANSACTION_KEY).commit()) {
            throw IllegalStateException("Unable to clear Nexus sign-in transaction")
        }
    }

    private fun readEncrypted(key: String): JSONObject? {
        val encoded = preferences.getString(key, null) ?: return null
        return try {
            val ciphertext = Base64.getDecoder().decode(encoded)
            JSONObject(crypto.decrypt(ciphertext).toString(Charsets.UTF_8))
        } catch (error: Exception) {
            // Keystore keys are intentionally not backed up. A restored or corrupted ciphertext
            // cannot be recovered and must not leave the app believing it is authenticated.
            Timber.w("Discarding unreadable Nexus OAuth storage (%s)", error.javaClass.simpleName)
            if (!preferences.edit().remove(key).commit()) {
                Timber.w("Unable to remove unreadable Nexus OAuth storage")
            }
            null
        }
    }

    private fun writeEncrypted(key: String, json: JSONObject) {
        val ciphertext = crypto.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.getEncoder().encodeToString(ciphertext)
        if (!preferences.edit().putString(key, encoded).commit()) {
            throw IllegalStateException("Unable to persist Nexus OAuth state")
        }
    }

    private fun tokensToJson(tokens: NexusStoredTokens): JSONObject = JSONObject()
        .put("version", STORAGE_VERSION)
        .put("access_token", tokens.accessToken)
        .put("refresh_token", tokens.refreshToken)
        .put("access_expires_at", tokens.accessTokenExpiresAtEpochSeconds)
        .apply {
            tokens.account?.let { account ->
                put(
                    "account",
                    JSONObject()
                        .put("id", account.id)
                        .put("name", account.name)
                        .put("membership_roles", JSONArray(account.membershipRoles)),
                )
            }
        }

    private fun tokensFromJson(json: JSONObject): NexusStoredTokens? {
        if (json.optInt("version") != STORAGE_VERSION) return null
        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")
        val expiresAt = json.optLong("access_expires_at", 0L)
        if (accessToken.isBlank() || refreshToken.isBlank() || expiresAt <= 0L) return null
        val account = json.optJSONObject("account")?.let { accountJson ->
            val rolesJson = accountJson.optJSONArray("membership_roles") ?: JSONArray()
            val roles = buildList {
                for (index in 0 until rolesJson.length()) {
                    rolesJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            NexusOAuthAccount(
                id = accountJson.optString("id"),
                name = accountJson.optString("name"),
                membershipRoles = roles,
            )
        }
        return NexusStoredTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresAtEpochSeconds = expiresAt,
            account = account,
        )
    }

    private fun transactionToJson(transaction: NexusAuthorizationTransaction): JSONObject = JSONObject()
        .put("version", STORAGE_VERSION)
        .put("state", transaction.state)
        .put("code_verifier", transaction.codeVerifier)
        .put("created_at", transaction.createdAtEpochMillis)

    private fun transactionFromJson(json: JSONObject): NexusAuthorizationTransaction? {
        if (json.optInt("version") != STORAGE_VERSION) return null
        val state = json.optString("state")
        val verifier = json.optString("code_verifier")
        val createdAt = json.optLong("created_at", 0L)
        if (state.isBlank() || verifier.isBlank() || createdAt <= 0L) return null
        return NexusAuthorizationTransaction(state, verifier, createdAt)
    }

    private companion object {
        private const val PREFERENCES_NAME = "nexus_oauth_secure"
        private const val TOKENS_KEY = "token_pair"
        private const val TRANSACTION_KEY = "authorization_transaction"
        private const val STORAGE_VERSION = 1
    }
}

private class NexusOAuthAesGcm {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
    private val keyLock = Any()

    fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH_BYTES + TAG_LENGTH_BYTES)
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, iv),
        )
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        existing?.secretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gamenative_nexus_oauth_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BYTES = 16
        private const val TAG_LENGTH_BITS = TAG_LENGTH_BYTES * 8
        private val ASSOCIATED_DATA = "GameNative:NexusOAuth:v1".toByteArray(Charsets.UTF_8)
    }
}
