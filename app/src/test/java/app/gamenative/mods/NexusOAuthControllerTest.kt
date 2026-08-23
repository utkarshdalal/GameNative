package app.gamenative.mods

import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusOAuthControllerTest {
    @Test
    fun pkce_usesRfc7636S256AndUrlSafeValues() {
        val pkce = NexusPkce.generate()
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(pkce.verifier.toByteArray(Charsets.US_ASCII)),
        )

        assertEquals(43, pkce.verifier.length)
        assertTrue(pkce.verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(expectedChallenge, pkce.challenge)
        assertFalse(pkce.challenge.contains('='))
        assertNotEquals(pkce.verifier, pkce.challenge)
    }

    @Test
    fun authorizationUrl_containsExactPublicClientPkceParameters() {
        val url = buildNexusAuthorizationUrl("challenge", "state").toHttpUrl()

        assertEquals("https://users.nexusmods.com/oauth/authorize", url.newBuilder().query(null).build().toString().trimEnd('/'))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("gamenative", url.queryParameter("client_id"))
        assertEquals("app.gamenative://oauth/callback", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("challenge", url.queryParameter("code_challenge"))
        assertEquals("state", url.queryParameter("state"))
        assertEquals(listOf("openid"), url.queryParameterValues("scope"))
        assertNull(url.queryParameter("client_secret"))
    }

    @Test
    fun callback_validatesStateConsumesTransactionAndStoresPair() = runBlocking {
        val store = MemoryOAuthStore().apply {
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        }
        val remote = FakeOAuthRemote()
        val controller = controller(store, remote, nowMillis = 2_000L)

        val result = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
        )

        assertTrue(result.isSuccess)
        assertEquals("authorization-code", remote.exchangedCode)
        assertEquals("verifier", remote.exchangedVerifier)
        assertNull(store.transaction)
        assertEquals("access-two", store.tokens?.accessToken)
        assertEquals("refresh-two", store.tokens?.refreshToken)
        assertEquals("Modder", store.tokens?.account?.name)
        assertTrue(controller.state.value.isConnected)
    }

    @Test
    fun callback_userInfoFailureKeepsAccountRecoveredFromAccessToken() = runBlocking {
        val store = MemoryOAuthStore().apply {
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        }
        val remote = FakeOAuthRemote(
            accessToken = nexusTestAccessToken(
                userId = 77L,
                username = "Token Modder",
                membershipRoles = listOf("member", "premium"),
            ),
            userInfoError = NexusOAuthException("userinfo unavailable"),
        )
        val controller = controller(store, remote, nowMillis = 2_000L)

        val result = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
        )

        assertTrue(result.isSuccess)
        assertEquals("77", result.getOrNull()?.id)
        assertEquals("Token Modder", store.tokens?.account?.name)
        assertTrue(store.tokens?.account?.isPremium == true)
        assertEquals("Token Modder", controller.state.value.account?.name)
        assertTrue(controller.state.value.isConnected)
    }

    @Test
    fun callback_successfulUserInfoEnrichesRecoveredTokenAccount() = runBlocking {
        val store = MemoryOAuthStore().apply {
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        }
        val remote = FakeOAuthRemote(
            accessToken = nexusTestAccessToken(username = "Token Modder"),
        )
        val controller = controller(store, remote, nowMillis = 2_000L)

        val result = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
        )

        assertTrue(result.isSuccess)
        assertEquals("Modder", store.tokens?.account?.name)
        assertTrue(store.tokens?.account?.isPremium == true)
    }

    @Test
    fun callback_accountMetadataPersistenceFailureKeepsInstalledSessionUsable() = runBlocking {
        val store = MemoryOAuthStore().apply {
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
            failTokenWriteAfter = 1
        }
        val remote = FakeOAuthRemote(
            accessToken = nexusTestAccessToken(username = "Token Modder"),
        )
        val controller = controller(store, remote, nowMillis = 2_000L)

        val result = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
        )

        assertTrue(result.isSuccess)
        assertEquals("Modder", result.getOrNull()?.name)
        assertEquals("Token Modder", store.tokens?.account?.name)
        assertEquals("Modder", controller.state.value.account?.name)
        assertTrue(controller.state.value.isConnected)
    }

    @Test
    fun callback_retriesMissingIdentityThenRejectsUnverifiableSession() = runBlocking {
        val store = MemoryOAuthStore().apply {
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        }
        val remote = FakeOAuthRemote(
            accessToken = "opaque-access-token",
            userInfoError = NexusOAuthException("userinfo unavailable"),
        )
        val controller = controller(store, remote, nowMillis = 2_000L)

        val result = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
        )

        assertTrue(result.isFailure)
        assertEquals(2, remote.userInfoCalls)
        assertNull(store.tokens)
        assertEquals(NexusConnectionState.DISCONNECTED, controller.state.value.connection)
    }

    @Test
    fun callback_canceledDuringOpaqueIdentityLookupDiscardsUnverifiedSession() = runBlocking {
        val userInfoStarted = CompletableDeferred<Unit>()
        val userInfoRelease = CompletableDeferred<Unit>()
        val store = MemoryOAuthStore().apply {
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        }
        val controller = controller(
            store,
            FakeOAuthRemote(
                accessToken = "opaque-access-token",
                userInfoStarted = userInfoStarted,
                userInfoRelease = userInfoRelease,
            ),
            nowMillis = 2_000L,
        )
        val callback = async {
            controller.handleAuthorizationCallback(
                "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
            )
        }
        userInfoStarted.await()

        callback.cancelAndJoin()

        assertNull(store.tokens)
        assertEquals(NexusConnectionState.DISCONNECTED, controller.state.value.connection)
    }

    @Test
    fun cancelAuthorization_duringExchangePreventsLateTokenInstallation() = runBlocking {
        val exchangeStarted = CompletableDeferred<Unit>()
        val exchangeRelease = CompletableDeferred<Unit>()
        val store = MemoryOAuthStore().apply {
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        }
        val remote = FakeOAuthRemote(
            exchangeStarted = exchangeStarted,
            exchangeRelease = exchangeRelease,
        )
        val controller = controller(store, remote, nowMillis = 2_000L)

        val callback = async {
            controller.handleAuthorizationCallback(
                "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
            )
        }
        exchangeStarted.await()

        controller.cancelAuthorization()
        assertNull(store.transaction)
        assertNull(store.tokens)
        assertEquals(NexusConnectionState.DISCONNECTED, controller.state.value.connection)

        exchangeRelease.complete(Unit)
        val result = callback.await()

        assertTrue(result.isFailure)
        assertNull(store.tokens)
        assertEquals(0, store.tokenWrites)
        assertEquals(NexusConnectionState.DISCONNECTED, controller.state.value.connection)
    }

    @Test
    fun cancelAuthorization_duringAccountSwitchPreservesExistingSession() = runBlocking {
        val exchangeStarted = CompletableDeferred<Unit>()
        val exchangeRelease = CompletableDeferred<Unit>()
        val priorTokens = expiredTokens().copy(
            accessTokenExpiresAtEpochSeconds = 10_000L,
            account = NexusOAuthAccount("7", "Prior Modder"),
        )
        val store = MemoryOAuthStore().apply {
            tokens = priorTokens
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        }
        val remote = FakeOAuthRemote(
            exchangeStarted = exchangeStarted,
            exchangeRelease = exchangeRelease,
        )
        val controller = controller(store, remote, nowMillis = 2_000L)

        val callback = async {
            controller.handleAuthorizationCallback(
                "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
            )
        }
        exchangeStarted.await()

        assertTrue(controller.state.value.isConnected)
        assertEquals("Prior Modder", controller.state.value.account?.name)
        controller.cancelAuthorization()
        exchangeRelease.complete(Unit)
        val result = callback.await()

        assertTrue(result.isFailure)
        assertEquals(priorTokens, store.tokens)
        assertEquals(0, store.tokenWrites)
        assertTrue(controller.state.value.isConnected)
        assertEquals("Prior Modder", controller.state.value.account?.name)
    }

    @Test
    fun callback_wrongStateDoesNotConsumeLegitimateTransaction() = runBlocking {
        val transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        val store = MemoryOAuthStore().apply { this.transaction = transaction }
        val remote = FakeOAuthRemote()
        val controller = controller(store, remote, nowMillis = 2_000L)

        val result = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/callback?code=authorization-code&state=attacker-state",
        )

        assertTrue(result.isFailure)
        assertEquals(transaction, store.transaction)
        assertEquals(0, remote.exchangeCalls)
        assertFalse(controller.state.value.isConnected)
    }

    @Test
    fun callback_expiredTransactionIsClearedWithoutExchange() = runBlocking {
        val store = MemoryOAuthStore().apply {
            transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        }
        val remote = FakeOAuthRemote()
        val now = 1_000L + NexusOAuthConfig.AUTH_TRANSACTION_TTL_MILLIS + 1L
        val controller = controller(store, remote, now)

        val result = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/callback?code=authorization-code&state=expected-state",
        )

        assertTrue(result.isFailure)
        assertNull(store.transaction)
        assertEquals(0, remote.exchangeCalls)
    }

    @Test
    fun callback_rejectsEncodedPathAndCodePlusError() = runBlocking {
        val transaction = NexusAuthorizationTransaction("expected-state", "verifier", 1_000L)
        val store = MemoryOAuthStore().apply { this.transaction = transaction }
        val remote = FakeOAuthRemote()
        val controller = controller(store, remote, nowMillis = 2_000L)

        val encodedPath = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/%63allback?code=code&state=expected-state",
        )
        val ambiguous = controller.handleAuthorizationCallback(
            "app.gamenative://oauth/callback?code=code&error=denied&state=expected-state",
        )

        assertTrue(encodedPath.isFailure)
        assertTrue(ambiguous.isFailure)
        assertEquals(transaction, store.transaction)
        assertEquals(0, remote.exchangeCalls)
    }

    @Test
    fun concurrentExpiryRefresh_isSingleFlightAndRotatesBothTokens() = runBlocking {
        val store = MemoryOAuthStore().apply { tokens = expiredTokens() }
        val remote = FakeOAuthRemote(refreshDelayMillis = 40L)
        val controller = controller(store, remote, nowMillis = 2_000_000L)

        val tokens = coroutineScope {
            List(8) { async { controller.getValidAccessToken() } }.awaitAll()
        }

        assertEquals(List(8) { "access-two" }, tokens)
        assertEquals(1, remote.refreshCalls)
        assertEquals("access-two", store.tokens?.accessToken)
        assertEquals("refresh-two", store.tokens?.refreshToken)
    }

    @Test
    fun concurrentUnauthorizedRefresh_reusesTokenAlreadyRotatedForRejectedToken() = runBlocking {
        val store = MemoryOAuthStore().apply { tokens = expiredTokens() }
        val remote = FakeOAuthRemote(refreshDelayMillis = 40L)
        val controller = controller(store, remote, nowMillis = 2_000_000L)

        val tokens = coroutineScope {
            List(8) {
                async {
                    controller.getValidAccessToken(
                        forceRefresh = true,
                        rejectedAccessToken = "access-one",
                    )
                }
            }.awaitAll()
        }
        val lateToken = controller.getValidAccessToken(
            forceRefresh = true,
            rejectedAccessToken = "access-one",
        )

        assertEquals(List(8) { "access-two" }, tokens)
        assertEquals("access-two", lateToken)
        assertEquals(1, remote.refreshCalls)
    }

    @Test
    fun refreshInvalidGrant_clearsSessionAndRequiresReconnect() = runBlocking {
        val store = MemoryOAuthStore().apply { tokens = expiredTokens() }
        val remote = FakeOAuthRemote(refreshError = NexusOAuthException("revoked", "invalid_grant"))
        var invalidations = 0
        val controller = controller(store, remote, nowMillis = 2_000_000L) { invalidations += 1 }

        val token = controller.getValidAccessToken()

        assertNull(token)
        assertNull(store.tokens)
        assertEquals(1, invalidations)
        assertEquals(NexusConnectionState.DISCONNECTED, controller.state.value.connection)
        assertEquals(NexusAuthError.SESSION_EXPIRED, controller.state.value.error)
    }

    @Test
    fun refreshStorageFailure_clearsSessionAndInvalidatesAccountBoundState() = runBlocking {
        val store = MemoryOAuthStore().apply {
            tokens = expiredTokens()
            failTokenWrites = true
        }
        val remote = FakeOAuthRemote()
        var invalidations = 0
        val controller = controller(store, remote, nowMillis = 2_000_000L) { invalidations += 1 }

        val error = runCatching { controller.getValidAccessToken() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertNull(store.tokens)
        assertEquals(1, invalidations)
        assertEquals(NexusConnectionState.DISCONNECTED, controller.state.value.connection)
    }

    @Test
    fun initializationWithoutStoredSession_invalidatesStaleAccountBoundState() {
        val store = MemoryOAuthStore()
        val remote = FakeOAuthRemote()
        var invalidations = 0

        controller(store, remote, nowMillis = 2_000_000L) { invalidations += 1 }

        assertEquals(1, invalidations)
    }

    @Test
    fun initialization_recoversAndPersistsAccountFromExistingStoredAccessToken() {
        val store = MemoryOAuthStore().apply {
            tokens = expiredTokens().copy(
                accessToken = nexusTestAccessToken(
                    userId = 88L,
                    username = "Recovered Modder",
                    membershipRoles = listOf("lifetime"),
                ),
                accessTokenExpiresAtEpochSeconds = 10_000L,
                account = null,
            )
        }
        val remote = FakeOAuthRemote()

        val controller = controller(store, remote, nowMillis = 2_000_000L)

        assertTrue(controller.state.value.isConnected)
        assertEquals("Recovered Modder", controller.state.value.account?.name)
        assertTrue(controller.state.value.account?.isPremium == true)
        assertEquals("Recovered Modder", store.tokens?.account?.name)
        assertEquals(1, store.tokenWrites)
        assertEquals(0, remote.exchangeCalls)
    }

    @Test
    fun disconnect_isLocalEvenWhenBestEffortRevocationFails() = runBlocking {
        val store = MemoryOAuthStore().apply {
            tokens = expiredTokens()
            transaction = NexusAuthorizationTransaction("state", "verifier", 1_000L)
        }
        val remote = FakeOAuthRemote(revocationFails = true)
        var invalidations = 0
        val controller = controller(store, remote, nowMillis = 2_000_000L) { invalidations += 1 }

        val result = controller.disconnect()

        assertTrue(result.isSuccess)
        assertNull(store.tokens)
        assertNull(store.transaction)
        assertEquals(1, invalidations)
        assertEquals(listOf("refresh_token", "access_token"), remote.revocationHints)
        assertEquals(NexusConnectionState.DISCONNECTED, controller.state.value.connection)
    }

    @Test
    fun disconnect_returnsStorageFailureInsteadOfThrowingIt() = runBlocking {
        val store = MemoryOAuthStore().apply {
            tokens = expiredTokens()
            failTokenClears = true
        }
        val remote = FakeOAuthRemote()
        var invalidations = 0
        val controller = controller(store, remote, nowMillis = 2_000_000L) { invalidations += 1 }

        val result = controller.disconnect()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(1, invalidations)
        assertTrue(controller.state.value.isConnected)
    }

    @Test
    fun disconnect_clearsCredentialsWhenTransactionCleanupFails() = runBlocking {
        val store = MemoryOAuthStore().apply {
            tokens = expiredTokens()
            transaction = NexusAuthorizationTransaction("state", "verifier", 1_000L)
            failTransactionClears = true
        }
        val remote = FakeOAuthRemote()
        var invalidations = 0
        val controller = controller(store, remote, nowMillis = 2_000_000L) { invalidations += 1 }

        val result = controller.disconnect()

        assertTrue(result.isFailure)
        assertNull(store.tokens)
        assertEquals("state", store.transaction?.state)
        assertEquals(1, invalidations)
        assertEquals(listOf("refresh_token", "access_token"), remote.revocationHints)
        assertEquals(NexusConnectionState.DISCONNECTED, controller.state.value.connection)
    }

    private fun controller(
        store: MemoryOAuthStore,
        remote: FakeOAuthRemote,
        nowMillis: Long,
        onSessionInvalidated: () -> Unit = {},
    ): NexusOAuthController = NexusOAuthController(
        store = store,
        remote = remote,
        clock = NexusOAuthClock { nowMillis },
        onSessionInvalidated = onSessionInvalidated,
    )

    private fun expiredTokens(): NexusStoredTokens = NexusStoredTokens(
        accessToken = "access-one",
        refreshToken = "refresh-one",
        accessTokenExpiresAtEpochSeconds = 1_000L,
        account = NexusOAuthAccount("42", "Modder"),
    )
}

private class MemoryOAuthStore : NexusOAuthStore {
    var tokens: NexusStoredTokens? = null
    var transaction: NexusAuthorizationTransaction? = null
    var tokenWrites: Int = 0
    var failTokenWrites: Boolean = false
    var failTokenWriteAfter: Int? = null
    var failTokenClears: Boolean = false
    var failTransactionClears: Boolean = false

    override fun readTokens(): NexusStoredTokens? = tokens

    override fun writeTokens(tokens: NexusStoredTokens) {
        if (failTokenWrites || tokenWrites >= (failTokenWriteAfter ?: Int.MAX_VALUE)) {
            throw IllegalStateException("storage full")
        }
        tokenWrites += 1
        this.tokens = tokens
    }

    override fun clearTokens() {
        if (failTokenClears) throw IllegalStateException("storage unavailable")
        tokens = null
    }

    override fun readTransaction(): NexusAuthorizationTransaction? = transaction

    override fun writeTransaction(transaction: NexusAuthorizationTransaction) {
        this.transaction = transaction
    }

    override fun clearTransaction() {
        if (failTransactionClears) throw IllegalStateException("transaction storage unavailable")
        transaction = null
    }
}

private class FakeOAuthRemote(
    private val refreshDelayMillis: Long = 0L,
    private val refreshError: NexusOAuthException? = null,
    private val revocationFails: Boolean = false,
    private val exchangeStarted: CompletableDeferred<Unit>? = null,
    private val exchangeRelease: CompletableDeferred<Unit>? = null,
    private val accessToken: String = "access-two",
    private val userInfoError: NexusOAuthException? = null,
    private val userInfoStarted: CompletableDeferred<Unit>? = null,
    private val userInfoRelease: CompletableDeferred<Unit>? = null,
) : NexusOAuthRemote {
    var exchangeCalls = 0
    var refreshCalls = 0
    var userInfoCalls = 0
    var exchangedCode: String? = null
    var exchangedVerifier: String? = null
    val revocationHints = mutableListOf<String>()

    override suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String): NexusTokenResponse {
        exchangeCalls += 1
        exchangedCode = code
        exchangedVerifier = codeVerifier
        exchangeStarted?.complete(Unit)
        exchangeRelease?.await()
        return tokenResponse()
    }

    override suspend fun refresh(refreshToken: String): NexusTokenResponse {
        refreshCalls += 1
        if (refreshDelayMillis > 0L) delay(refreshDelayMillis)
        refreshError?.let { throw it }
        return tokenResponse()
    }

    override suspend fun getUserInfo(accessToken: String): NexusOAuthAccount {
        userInfoCalls += 1
        userInfoStarted?.complete(Unit)
        userInfoRelease?.await()
        userInfoError?.let { throw it }
        return NexusOAuthAccount("42", "Modder", membershipRoles = listOf("premium"))
    }

    override suspend fun revoke(token: String, tokenTypeHint: String) {
        revocationHints += tokenTypeHint
        if (revocationFails) throw NexusOAuthException("offline")
    }

    private fun tokenResponse(): NexusTokenResponse = NexusTokenResponse(
        accessToken = accessToken,
        refreshToken = "refresh-two",
        tokenType = "Bearer",
        expiresInSeconds = 3_600L,
        createdAtEpochSeconds = 2_000L,
    )
}
