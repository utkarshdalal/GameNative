package app.gamenative.mods

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NexusOAuthServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var service: NexusOAuthService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        service = NexusOAuthService(
            client = client,
            endpoints = NexusOAuthEndpoints(
                token = server.url("/oauth/token").toString(),
                revocation = server.url("/oauth/revoke").toString(),
                userInfo = server.url("/oauth/userinfo").toString(),
            ),
        )
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun exchange_usesPublicPkceFlowWithoutClientSecret() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "access_token": "access-one",
                  "refresh_token": "refresh-one",
                  "token_type": "bearer",
                  "expires_in": 3600,
                  "scope": "public openid",
                  "created_at": 1000
                }
                """.trimIndent(),
            ),
        )

        val response = service.exchangeAuthorizationCode("authorization-code", "pkce-verifier")

        assertEquals("access-one", response.accessToken)
        assertEquals("refresh-one", response.refreshToken)
        assertEquals("Bearer", response.tokenType)
        val request = server.takeRequest()
        assertEquals("/oauth/token", request.path)
        val form = request.body.readUtf8()
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("client_id=gamenative"))
        assertTrue(form.contains("redirect_uri=app.gamenative%3A%2F%2Foauth%2Fcallback"))
        assertTrue(form.contains("code=authorization-code"))
        assertTrue(form.contains("code_verifier=pkce-verifier"))
        assertFalse(form.contains("client_secret"))
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun refresh_acceptsAndReturnsRotatedTokenPair() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "access_token": "access-two",
                  "refresh_token": "refresh-two",
                  "token_type": "Bearer",
                  "expires_in": 1800
                }
                """.trimIndent(),
            ),
        )

        val response = service.refresh("refresh-one")

        assertEquals("access-two", response.accessToken)
        assertEquals("refresh-two", response.refreshToken)
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=refresh-one"))
        assertFalse(form.contains("client_secret"))
    }

    @Test
    fun tokenResponse_rejectsNonBearerTokenType() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "access_token": "access-one",
                  "refresh_token": "refresh-one",
                  "token_type": "mac",
                  "expires_in": 3600
                }
                """.trimIndent(),
            ),
        )

        val error = runCatching { service.refresh("refresh-one") }.exceptionOrNull()

        assertTrue(error is NexusOAuthException)
    }

    @Test
    fun invalidGrant_isClassifiedForDisconnectHandling() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody(
                    """
                    {
                      "error": "invalid_grant",
                      "error_description": "Refresh token was revoked"
                    }
                    """.trimIndent(),
                ),
        )

        val error = runCatching { service.refresh("revoked") }.exceptionOrNull()

        assertTrue(error is NexusOAuthException)
        assertTrue((error as NexusOAuthException).isInvalidGrant)
    }

    @Test
    fun revoke_usesPublicClientIdAndTokenHint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        service.revoke("refresh-one", "refresh_token")

        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("client_id=gamenative"))
        assertTrue(form.contains("token=refresh-one"))
        assertTrue(form.contains("token_type_hint=refresh_token"))
        assertFalse(form.contains("client_secret"))
    }

    @Test
    fun userInfo_parsesAccountAndPremiumRole() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "sub": "42",
                  "name": "Modder",
                  "avatar": "https://example.test/avatar.png",
                  "membership_roles": ["member", "premium"]
                }
                """.trimIndent(),
            ),
        )

        val account = service.getUserInfo("access-one")

        assertEquals("42", account.id)
        assertEquals("Modder", account.name)
        assertTrue(account.isPremium)
        assertEquals("Bearer access-one", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun userInfo_rejectsNonNumericNexusUserId() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "sub": "opaque-subject",
                  "name": "Modder",
                  "membership_roles": []
                }
                """.trimIndent(),
            ),
        )

        val error = runCatching { service.getUserInfo("access-one") }.exceptionOrNull()

        assertTrue(error is NexusOAuthException)
    }

    @Test
    fun userInfo_rejectsMissingMembershipRoles() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"sub":"42","name":"Modder"}""",
            ),
        )

        val error = runCatching { service.getUserInfo("access-one") }.exceptionOrNull()

        assertTrue(error is NexusOAuthException)
    }
}
