package app.gamenative.mods

import android.content.Context
import android.net.Uri
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber

internal fun interface NexusOAuthClock {
    fun currentTimeMillis(): Long
}

private object SystemNexusOAuthClock : NexusOAuthClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

private data class NexusAuthorizationExchange(
    val state: String,
    val code: String,
    val codeVerifier: String,
)

internal class NexusOAuthController(
    private val store: NexusOAuthStore,
    private val remote: NexusOAuthRemote,
    private val clock: NexusOAuthClock = SystemNexusOAuthClock,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val onSessionInvalidated: () -> Unit = {},
) {
    private val sessionMutex = Mutex()
    private val authorizationLock = Any()
    private var activeAuthorizationState: String? = store.readTransaction()?.state
    private val mutableState = MutableStateFlow(stateFromStoredTokens())

    val state: StateFlow<NexusAuthState> = mutableState.asStateFlow()

    init {
        if (!mutableState.value.isConnected) {
            invalidateSessionSideData()
        }
    }

    fun beginAuthorization(): Uri {
        val pkce = NexusPkce.generate(secureRandom)
        val oauthState = NexusPkce.generateState(secureRandom)
        synchronized(authorizationLock) {
            store.writeTransaction(
                NexusAuthorizationTransaction(
                    state = oauthState,
                    codeVerifier = pkce.verifier,
                    createdAtEpochMillis = clock.currentTimeMillis(),
                ),
            )
            activeAuthorizationState = oauthState
            if (store.readTokens() == null) {
                mutableState.value = NexusAuthState(connection = NexusConnectionState.CONNECTING)
            }
        }
        return Uri.parse(buildNexusAuthorizationUrl(pkce.challenge, oauthState))
    }

    fun cancelAuthorization() {
        synchronized(authorizationLock) {
            // Clear the in-memory attempt first. This is the cancellation linearization point:
            // an exchange response that arrives later cannot install its token pair.
            activeAuthorizationState = null
            try {
                store.clearTransaction()
            } finally {
                mutableState.value = stateFromStoredTokens()
            }
        }
    }

    suspend fun handleAuthorizationCallback(callbackUri: Uri): Result<NexusOAuthAccount?> =
        handleAuthorizationCallback(callbackUri.toString())

    internal suspend fun handleAuthorizationCallback(callbackUri: String): Result<NexusOAuthAccount?> =
        sessionMutex.withLock {
            var attemptState: String? = null
            var installedAccessToken: String? = null
            try {
                val callback = parseAndValidateCallback(callbackUri)
                val exchange = synchronized(authorizationLock) {
                    val transaction = validateTransaction(callback)
                    if (activeAuthorizationState != transaction.state) {
                        throw NexusOAuthException("This Nexus sign-in request is no longer active")
                    }
                    attemptState = transaction.state

                    // Authorization codes and PKCE transactions are one-time credentials. Consume
                    // the durable transaction before the network exchange so duplicate intents
                    // cannot replay it. The in-memory attempt remains active until install/cancel.
                    store.clearTransaction()

                    callback.error?.let { errorCode ->
                        throw NexusOAuthException(
                            message = callback.errorDescription ?: "Nexus sign-in was not approved",
                            errorCode = errorCode,
                        )
                    }
                    val code = callback.code
                        ?: throw NexusOAuthException("Nexus sign-in did not return an authorization code")

                    // Keep an existing account visible while the user switches accounts. With no
                    // prior session, CONNECTING remains the useful UI state.
                    if (store.readTokens() == null) {
                        mutableState.value = NexusAuthState(connection = NexusConnectionState.CONNECTING)
                    }
                    NexusAuthorizationExchange(
                        state = transaction.state,
                        code = code,
                        codeVerifier = transaction.codeVerifier,
                    )
                }

                val response = remote.exchangeAuthorizationCode(exchange.code, exchange.codeVerifier)
                val refreshToken = response.refreshToken
                    ?.takeIf(String::isNotBlank)
                    ?: throw NexusOAuthException("Nexus did not return a refresh token")
                val tokenAccount = nexusAccountFromAccessToken(response.accessToken)
                val storedTokens = response.toStoredTokens(
                    refreshToken = refreshToken,
                    account = tokenAccount,
                )
                val tokensInstalled = synchronized(authorizationLock) {
                    if (activeAuthorizationState != exchange.state) {
                        false
                    } else {
                        store.writeTokens(storedTokens)
                        installedAccessToken = storedTokens.accessToken
                        activeAuthorizationState = null
                        invalidateSessionSideData()
                        mutableState.value = NexusAuthState(
                            connection = if (tokenAccount == null) {
                                NexusConnectionState.CONNECTING
                            } else {
                                NexusConnectionState.CONNECTED
                            },
                            account = tokenAccount,
                        )
                        true
                    }
                }
                if (!tokensInstalled) {
                    return@withLock Result.failure(
                        NexusOAuthException("This Nexus sign-in request was canceled or replaced"),
                    )
                }

                suspend fun loadAccount(): NexusOAuthAccount? = try {
                    remote.getUserInfo(response.accessToken)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(
                        "Nexus sign-in succeeded but account lookup failed (%s)",
                        error.javaClass.simpleName,
                    )
                    null
                }
                var enrichedAccount = loadAccount()
                if (enrichedAccount == null && tokenAccount == null) {
                    // An opaque token gives us no local identity to bind signed file grants to.
                    // Retry once before rejecting the otherwise successful token exchange.
                    enrichedAccount = loadAccount()
                }
                val account = enrichedAccount ?: tokenAccount
                if (account == null) {
                    synchronized(authorizationLock) {
                        if (store.readTokens()?.accessToken == response.accessToken) {
                            runCatching { store.clearTokens() }
                            invalidateSessionSideData()
                            mutableState.value = NexusAuthState(
                                connection = NexusConnectionState.DISCONNECTED,
                                error = NexusAuthError.SIGN_IN_FAILED,
                            )
                        }
                    }
                    throw NexusOAuthException("Nexus account identity could not be verified")
                }
                if (enrichedAccount != null) {
                    synchronized(authorizationLock) {
                        val current = store.readTokens()
                        if (current?.accessToken == response.accessToken) {
                            try {
                                store.writeTokens(current.copy(account = enrichedAccount))
                            } catch (storageError: Exception) {
                                // The durable token pair is already installed. Account metadata is
                                // an enrichment and can be recovered again from the access token.
                                Timber.w(
                                    "Unable to persist Nexus account metadata (%s)",
                                    storageError.javaClass.simpleName,
                                )
                            }
                            mutableState.value = NexusAuthState(
                                connection = NexusConnectionState.CONNECTED,
                                account = enrichedAccount,
                            )
                        }
                    }
                }
                Result.success(account)
            } catch (error: CancellationException) {
                restoreStateAfterAuthorizationFailure(attemptState)
                synchronized(authorizationLock) {
                    val canceledToken = installedAccessToken
                    val stored = store.readTokens()
                    if (
                        activeAuthorizationState == null &&
                        canceledToken != null &&
                        stored?.accessToken == canceledToken &&
                        stored.account == null &&
                        nexusAccountFromAccessToken(stored.accessToken) == null
                    ) {
                        // An opaque token has no locally verifiable account identity. If its
                        // lookup is canceled, discard it instead of leaving a permanent
                        // CONNECTING session that cannot safely authorize downloads.
                        runCatching { store.clearTokens() }
                        activeAuthorizationState = null
                        invalidateSessionSideData()
                        mutableState.value = NexusAuthState(
                            connection = NexusConnectionState.DISCONNECTED,
                        )
                    }
                }
                throw error
            } catch (error: Exception) {
                restoreStateAfterAuthorizationFailure(attemptState, NexusAuthError.SIGN_IN_FAILED)
                Result.failure(error)
            }
        }

    suspend fun getValidAccessToken(
        forceRefresh: Boolean = false,
        rejectedAccessToken: String? = null,
    ): String? {
        val initial = store.readTokens() ?: return null
        if (forceRefresh && rejectedAccessToken != null && initial.accessToken != rejectedAccessToken) {
            return initial.accessToken
        }
        val nowSeconds = clock.currentTimeMillis().toEpochSeconds()
        if (!forceRefresh && initial.isFreshAt(nowSeconds)) return initial.accessToken

        return sessionMutex.withLock {
            val current = store.readTokens() ?: return@withLock null
            val lockedNowSeconds = clock.currentTimeMillis().toEpochSeconds()
            val rejectedTokenWasAlreadyReplaced =
                forceRefresh && rejectedAccessToken != null && current.accessToken != rejectedAccessToken
            val anotherRefreshCompleted =
                current.accessToken != initial.accessToken || current.refreshToken != initial.refreshToken
            if (
                rejectedTokenWasAlreadyReplaced ||
                anotherRefreshCompleted ||
                (!forceRefresh && current.isFreshAt(lockedNowSeconds))
            ) {
                return@withLock current.accessToken
            }

            val refreshed = try {
                val response = remote.refresh(current.refreshToken)
                val rotatedRefreshToken = response.refreshToken
                    ?.takeIf(String::isNotBlank)
                    ?: current.refreshToken
                response.toStoredTokens(
                    refreshToken = rotatedRefreshToken,
                    account = nexusAccountFromAccessToken(response.accessToken) ?: current.account,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is NexusOAuthException && error.isInvalidGrant) {
                    runCatching { store.clearTokens() }
                    invalidateSessionSideData()
                    mutableState.value = NexusAuthState(
                        connection = NexusConnectionState.DISCONNECTED,
                        error = NexusAuthError.SESSION_EXPIRED,
                    )
                    return@withLock null
                }

                // During the refresh skew window, a transient refresh outage should not discard an
                // access token that the server may still accept until its actual expiry.
                if (!forceRefresh && current.accessTokenExpiresAtEpochSeconds > lockedNowSeconds) {
                    mutableState.value = NexusAuthState(
                        connection = NexusConnectionState.CONNECTED,
                        account = current.account,
                        error = NexusAuthError.REFRESH_RETRY_PENDING,
                    )
                    return@withLock current.accessToken
                }
                throw error
            }

            try {
                store.writeTokens(refreshed)
            } catch (storageError: Exception) {
                // A rotating refresh-token server may already have invalidated the old token.
                // Never continue using a session whose new pair could not be saved atomically.
                runCatching { store.clearTokens() }
                invalidateSessionSideData()
                mutableState.value = NexusAuthState(
                    connection = NexusConnectionState.DISCONNECTED,
                    error = NexusAuthError.CREDENTIAL_STORAGE_FAILED,
                )
                throw storageError
            }
            mutableState.value = NexusAuthState(
                connection = NexusConnectionState.CONNECTED,
                account = refreshed.account,
            )
            refreshed.accessToken
        }
    }

    suspend fun disconnect(): Result<Unit> {
        val outcome = try {
            sessionMutex.withLock {
                var cleanupFailure: Throwable? = null
                fun recordFailure(error: Throwable) {
                    cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
                }

                val current = try {
                    store.readTokens()
                } catch (error: Exception) {
                    recordFailure(error)
                    null
                }

                var credentialsCleared = false
                try {
                    store.clearTokens()
                    credentialsCleared = true
                } catch (error: Exception) {
                    recordFailure(error)
                }
                try {
                    store.clearTransaction()
                } catch (error: Exception) {
                    recordFailure(error)
                }
                try {
                    invalidateSessionSideData()
                } catch (error: Exception) {
                    recordFailure(error)
                }
                if (credentialsCleared) {
                    mutableState.value = NexusAuthState(connection = NexusConnectionState.DISCONNECTED)
                }
                LocalDisconnectOutcome(current, cleanupFailure)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.failure(error)
        }

        var revocationFailures = 0
        outcome.tokens?.let { tokens ->
            val revocations = listOf(
                tokens.refreshToken to "refresh_token",
                tokens.accessToken to "access_token",
            ).distinctBy { it.first }
            for ((token, hint) in revocations) {
                try {
                    remote.revoke(token, hint)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    revocationFailures += 1
                }
            }
        }
        if (revocationFailures > 0) {
            Timber.w("Nexus account disconnected locally; %d revocation request(s) failed", revocationFailures)
        }
        return outcome.cleanupFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    fun hasStoredSession(): Boolean = state.value.isConnected

    private fun restoreStateAfterAuthorizationFailure(
        attemptState: String?,
        error: NexusAuthError? = null,
    ) {
        synchronized(authorizationLock) {
            if (attemptState == null) {
                mutableState.value = stateFromStoredTokens(error)
            } else if (activeAuthorizationState == attemptState) {
                activeAuthorizationState = null
                mutableState.value = stateFromStoredTokens(error)
            }
        }
    }

    private fun validateTransaction(callback: NexusAuthorizationCallback): NexusAuthorizationTransaction {
        val transaction = store.readTransaction()
            ?: throw NexusOAuthException("This Nexus sign-in request is no longer active")
        val ageMillis = clock.currentTimeMillis() - transaction.createdAtEpochMillis
        if (ageMillis < -MAX_CLOCK_SKEW_MILLIS || ageMillis > NexusOAuthConfig.AUTH_TRANSACTION_TTL_MILLIS) {
            store.clearTransaction()
            throw NexusOAuthException("This Nexus sign-in request expired. Please try again.")
        }
        val returnedState = callback.state
            ?: throw NexusOAuthException("Nexus sign-in returned without its security state")
        if (!statesMatch(transaction.state, returnedState)) {
            // Do not let an unrelated/malicious callback cancel the legitimate browser flow.
            throw NexusOAuthException("Nexus sign-in security validation failed")
        }
        return transaction
    }

    private fun stateFromStoredTokens(error: NexusAuthError? = null): NexusAuthState {
        val tokens = store.readTokens()
        return if (tokens == null) {
            NexusAuthState(
                connection = NexusConnectionState.DISCONNECTED,
                error = error,
            )
        } else {
            val account = tokens.account ?: nexusAccountFromAccessToken(tokens.accessToken)
            if (tokens.account == null && account != null) {
                try {
                    store.writeTokens(tokens.copy(account = account))
                } catch (storageError: Exception) {
                    // The existing token pair is still usable. Keep the derived account in memory
                    // and retry persistence on a later token rotation instead of forcing re-login.
                    Timber.w(
                        "Unable to persist Nexus account metadata recovered from access token (%s)",
                        storageError.javaClass.simpleName,
                    )
                }
            }
            NexusAuthState(
                connection = NexusConnectionState.CONNECTED,
                account = account,
                error = error,
            )
        }
    }

    private fun invalidateSessionSideData() {
        try {
            onSessionInvalidated()
        } catch (error: Exception) {
            Timber.w("Unable to clear stale Nexus download authorization state (%s)", error.javaClass.simpleName)
        }
    }

    private fun NexusTokenResponse.toStoredTokens(
        refreshToken: String,
        account: NexusOAuthAccount? = null,
    ): NexusStoredTokens {
        if (
            accessToken.isBlank() ||
            refreshToken.isBlank() ||
            expiresInSeconds <= 0L ||
            !tokenType.equals("Bearer", ignoreCase = true)
        ) {
            throw NexusOAuthException("Nexus returned an invalid Bearer token response")
        }
        val nowSeconds = clock.currentTimeMillis().toEpochSeconds()
        val issuedAt = createdAtEpochSeconds
            ?.takeIf { it in (nowSeconds - MAX_SERVER_CLOCK_DIFFERENCE_SECONDS)..(nowSeconds + MAX_SERVER_CLOCK_DIFFERENCE_SECONDS) }
            ?: nowSeconds
        val expiresAt = if (expiresInSeconds > Long.MAX_VALUE - issuedAt) {
            Long.MAX_VALUE
        } else {
            issuedAt + expiresInSeconds
        }
        return NexusStoredTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresAtEpochSeconds = expiresAt,
            account = account,
        )
    }

    private fun NexusStoredTokens.isFreshAt(nowEpochSeconds: Long): Boolean =
        accessTokenExpiresAtEpochSeconds - NexusOAuthConfig.ACCESS_TOKEN_EXPIRY_SKEW_SECONDS > nowEpochSeconds

    private companion object {
        private const val MAX_CLOCK_SKEW_MILLIS = 30_000L
        private val MAX_SERVER_CLOCK_DIFFERENCE_SECONDS = TimeUnit.MINUTES.toSeconds(10)
    }
}

/** Process-wide entry point used by UI and by the default authenticated [NexusApiClient]. */
object NexusAuthManager {
    private val initializationLock = Any()
    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(
        NexusAuthState(connection = NexusConnectionState.CONNECTING),
    )

    @Volatile
    private var controller: NexusOAuthController? = null

    @Volatile
    private var applicationContext: Context? = null

    val state: StateFlow<NexusAuthState> = mutableState.asStateFlow()

    fun initialize(context: Context) {
        synchronized(initializationLock) {
            if (applicationContext == null) {
                applicationContext = context.applicationContext
            }
        }
        initializationScope.launch { requireController() }
    }

    suspend fun beginAuthorization(): Uri = withContext(Dispatchers.IO) {
        requireController().beginAuthorization()
    }

    suspend fun cancelAuthorization() = withContext(Dispatchers.IO) {
        requireController().cancelAuthorization()
    }

    suspend fun handleAuthorizationCallback(callbackUri: Uri): Result<NexusOAuthAccount?> =
        withContext(Dispatchers.IO) {
            requireController().handleAuthorizationCallback(callbackUri)
        }

    suspend fun getValidAccessToken(
        forceRefresh: Boolean = false,
        rejectedAccessToken: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        requireController().getValidAccessToken(forceRefresh, rejectedAccessToken)
    }

    suspend fun hasStoredSession(): Boolean = withContext(Dispatchers.IO) {
        requireController().hasStoredSession()
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        requireController().disconnect()
    }

    private fun requireController(): NexusOAuthController {
        controller?.let { return it }
        return synchronized(initializationLock) {
            controller ?: createController(
                checkNotNull(applicationContext) { "NexusAuthManager has not been initialized" },
            ).also { created ->
                controller = created
                mutableState.value = created.state.value
                initializationScope.launch {
                    created.state.collect { state -> mutableState.value = state }
                }
            }
        }
    }

    private fun createController(context: Context): NexusOAuthController = NexusOAuthController(
        store = AndroidNexusOAuthStore(context),
        remote = NexusOAuthService(),
        onSessionInvalidated = {
            NexusDownloadLinkInbox.clearAll()
            NexusPendingDownloadStore.clear(context)
        },
    )
}

private data class NexusAuthorizationCallback(
    val state: String?,
    val code: String?,
    val error: String?,
    val errorDescription: String?,
)

private data class LocalDisconnectOutcome(
    val tokens: NexusStoredTokens?,
    val cleanupFailure: Throwable?,
)

internal fun buildNexusAuthorizationUrl(codeChallenge: String, state: String): String =
    NexusOAuthConfig.AUTHORIZATION_ENDPOINT.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("scope", NexusOAuthConfig.SCOPE)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("client_id", NexusOAuthConfig.CLIENT_ID)
        .addQueryParameter("redirect_uri", NexusOAuthConfig.REDIRECT_URI)
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("state", state)
        .build()
        .toString()

private fun parseAndValidateCallback(callbackUri: String): NexusAuthorizationCallback {
    val uri = try {
        URI(callbackUri)
    } catch (error: Exception) {
        throw NexusOAuthException("Nexus returned an invalid callback", cause = error)
    }
    if (
        !uri.scheme.equals("app.gamenative", ignoreCase = true) ||
        !uri.host.equals("oauth", ignoreCase = true) ||
        uri.rawPath != "/callback" ||
        uri.userInfo != null ||
        uri.port != -1 ||
        uri.rawFragment != null
    ) {
        throw NexusOAuthException("Nexus returned an unexpected callback address")
    }
    val parameters = parseQuery(uri.rawQuery)
    val callback = NexusAuthorizationCallback(
        state = parameters.singleValue("state"),
        code = parameters.singleValue("code")?.takeIf(String::isNotBlank),
        error = parameters.singleValue("error")?.takeIf(String::isNotBlank),
        errorDescription = parameters.singleValue("error_description")?.takeIf(String::isNotBlank),
    )
    if ((callback.code == null) == (callback.error == null)) {
        throw NexusOAuthException("Nexus returned an ambiguous sign-in callback")
    }
    return callback
}

private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    val values = linkedMapOf<String, MutableList<String>>()
    for (part in rawQuery.split('&')) {
        if (part.isBlank()) continue
        val rawName = part.substringBefore('=')
        val rawValue = part.substringAfter('=', "")
        val name = URLDecoder.decode(rawName, Charsets.UTF_8.name())
        val value = URLDecoder.decode(rawValue, Charsets.UTF_8.name())
        values.getOrPut(name) { mutableListOf() }.add(value)
    }
    return values
}

private fun Map<String, List<String>>.singleValue(name: String): String? {
    val values = get(name) ?: return null
    if (values.size != 1) {
        throw NexusOAuthException("Nexus returned a duplicated $name parameter")
    }
    return values.single()
}

private fun Long.toEpochSeconds(): Long = this / 1000L
