package app.gamenative.service.gog

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    application = android.app.Application::class
)
class GOGAuthManagerTest {
    @Mock
    private lateinit var context: Context

    private lateinit var mockWebServer: MockWebServer
    private lateinit var closeable: AutoCloseable
    private lateinit var tempDir: File

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            // Silence Timber logging in all tests
            Timber.uprootAll()
        }
    }

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        mockWebServer = MockWebServer()
        mockWebServer.start()
        tempDir = createTempDir("gogtest")
        `when`(context.filesDir).thenReturn(tempDir)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        closeable.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testAuthenticateWithCode_success() = runTest {
        // Arrange
        val code = "good_code"
        val json = JSONObject().apply {
            put("access_token", "token123")
            put("refresh_token", "refresh123")
            put("user_id", "user123")
            put("expires_in", 3600)
        }.toString()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(json)
            .addHeader("Content-Type", "application/json"))

        // Act
        val result = withMockedHttpClient(mockWebServer.url("/token").toString()) {
            GOGAuthManager.authenticateWithCode(context, code)
        }

        // Assert
        assertTrue(result.isSuccess)
        val creds = result.getOrNull()!!
        assertEquals("token123", creds.accessToken)
        assertEquals("refresh123", creds.refreshToken)
        assertEquals("user123", creds.userId)

        // Verify request
        val request = mockWebServer.takeRequest()
        assertTrue(request.path?.startsWith("/token?") == true)
        assertTrue(request.path?.contains("authorization_code") == true)
    }

    @Test
    fun testAuthenticateWithCode_failure() = runTest {
        // Arrange
        val code = "bad_code"
        val json = JSONObject().apply {
            put("error", "invalid_grant")
            put("error_description", "Invalid code")
        }.toString()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(400)
            .setBody(json)
            .addHeader("Content-Type", "application/json"))

        // Act
        val result = withMockedHttpClient(mockWebServer.url("/token").toString()) {
            GOGAuthManager.authenticateWithCode(context, code)
        }

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid code") == true)
    }

    @Test
    fun testGetStoredCredentials_success() = runTest {
        val authJson = JSONObject().apply {
            put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                put("access_token", "token123")
                put("refresh_token", "refresh123")
                put("user_id", "user123")
                put("expires_in", 3600)
                put("loginTime", System.currentTimeMillis() / 1000.0 + 1000)
            })
        }.toString()
        val authFile = File(tempDir, "gog_auth.json")
        authFile.writeText(authJson)

        val result = GOGAuthManager.getStoredCredentials(context)
        assertTrue(result.isSuccess)
        val creds = result.getOrNull()!!
        assertEquals("token123", creds.accessToken)
    }

    @Test
    fun testGetStoredCredentials_expired_refreshSuccess() = runTest {
        // Arrange
        val authJson = JSONObject().apply {
            put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                put("access_token", "old_token")
                put("refresh_token", "refresh123")
                put("user_id", "user123")
                put("expires_in", 1)
                put("loginTime", 0)
            })
        }.toString()
        val authFile = File(tempDir, "gog_auth.json")
        authFile.writeText(authJson)

        // Mock refresh token response
        val refreshJson = JSONObject().apply {
            put("access_token", "new_token")
            put("refresh_token", "refresh123")
            put("user_id", "user123")
            put("expires_in", 3600)
        }.toString()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(refreshJson)
            .addHeader("Content-Type", "application/json"))

        // Act
        val result = withMockedHttpClient(mockWebServer.url("/token").toString()) {
            GOGAuthManager.getStoredCredentials(context)
        }

        // Assert
        assertTrue(result.isSuccess)
        val creds = result.getOrNull()!!
        assertEquals("new_token", creds.accessToken)

        // Verify refresh request
        val request = mockWebServer.takeRequest()
        assertTrue(request.path?.contains("refresh_token") == true)
    }

    @Test
    fun testValidateCredentials_success() = runTest {
        val authJson = JSONObject().apply {
            put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                put("access_token", "token123")
                put("refresh_token", "refresh123")
                put("user_id", "user123")
                put("expires_in", 3600)
                put("loginTime", System.currentTimeMillis() / 1000.0 + 1000)
            })
        }.toString()
        val authFile = File(tempDir, "gog_auth.json")
        authFile.writeText(authJson)

        val result = GOGAuthManager.validateCredentials(context)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
    }

    @Test
    fun testValidateCredentials_failure() = runTest {
        // No file created, so should fail
        val result = GOGAuthManager.validateCredentials(context)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == false)
    }

    @Test
    fun testExtractCodeFromInput_withFullUrl() {
        val url = "https://embed.gog.com/on_login_success?code=ABC123XYZ&origin=client"
        val result = GOGAuthManager.extractCodeFromInput(url)
        assertEquals("ABC123XYZ", result)
    }

    @Test
    fun testExtractCodeFromInput_withUrlMultipleParams() {
        val url = "https://embed.gog.com/on_login_success?code=DEF456&origin=client&state=test"
        val result = GOGAuthManager.extractCodeFromInput(url)
        assertEquals("DEF456", result)
    }

    @Test
    fun testExtractCodeFromInput_withUrlNoCode() {
        val url = "https://embed.gog.com/on_login_success?origin=client"
        val result = GOGAuthManager.extractCodeFromInput(url)
        assertEquals("", result)
    }

    @Test
    fun testExtractCodeFromInput_withPlainCode() {
        val code = "PLAIN_CODE_123"
        val result = GOGAuthManager.extractCodeFromInput(code)
        assertEquals("PLAIN_CODE_123", result)
    }

    // --- Helpers ---
    private suspend fun <T> withMockedHttpClient(testTokenUrl: String, block: suspend () -> T): T {
        // Override the token URL to point to MockWebServer
        val originalTokenUrl = GOGAuthManager.tokenUrl
        GOGAuthManager.tokenUrl = testTokenUrl

        try {
            return block()
        } finally {
            GOGAuthManager.tokenUrl = originalTokenUrl
        }
    }
}
