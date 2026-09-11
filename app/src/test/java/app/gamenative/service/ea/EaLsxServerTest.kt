package app.gamenative.service.ea

import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EaLsxServerTest {
    @Before
    fun startServer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        mockkObject(EaAuthManager)
        every { EaAuthManager.credentials(any()) } returns null
        EaLsxServer.start(EaLaunchSession(context, context.cacheDir, File(context.cacheDir, "nfs"), "C:\\NFS", "NFS13.exe", "", 1262560))
    }

    @After
    fun stopServer() {
        EaLsxServer.stop()
        unmockkObject(EaAuthManager)
    }

    private fun request(frame: String): String = Socket("127.0.0.1", EaConstants.LSX_PORT).use { socket ->
        socket.soTimeout = 3000
        val input = socket.getInputStream()
        fun readFrame(): String = buildString {
            while (true) {
                val byte = input.read()
                if (byte <= 0) break
                append(byte.toChar())
            }
        }
        readFrame() // Initial challenge; plaintext requests use the same dispatcher.
        socket.getOutputStream().write((frame + '\u0000').toByteArray())
        readFrame()
    }

    @Test
    fun `Most Wanted legacy auth request receives refreshed token with matching routing`() {
        coEvery { EaAuthManager.opaqueLaunchToken(any()) } returns "fresh<&\"token"
        assertEquals(
            "<LSX><Response id=\"8\" sender=\"Utility\"><AuthToken value=\"fresh&lt;&amp;&quot;token\"/></Response></LSX>",
            request("<LSX><Request recipient=\"EbisuSDK\" id=\"8\"><GetAuthToken version=\"2\"/></Request></LSX>"),
        )
    }

    @Test
    fun `modern client auth code request still uses its client id and scope`() {
        coEvery { EaAuthManager.authCodeFor(any(), "game-client", "signin") } returns "game-code"
        assertEquals(
            "<LSX><Response id=\"9\" sender=\"Utility\"><AuthCode value=\"game-code\"/></Response></LSX>",
            request("<LSX><Request recipient=\"Utility\" id=\"9\"><GetAuthCode ClientId=\"game-client\" Scope=\"signin\"/></Request></LSX>"),
        )
    }

    @Test
    fun `existing access token request remains supported`() {
        coEvery { EaAuthManager.accessToken(any()) } returns "access-token"
        assertEquals(
            "<LSX><Response id=\"10\" sender=\"Utility\"><AuthToken value=\"access-token\"/></Response></LSX>",
            request("<LSX><Request recipient=\"Utility\" id=\"10\"><GetAccessToken/></Request></LSX>"),
        )
    }

    @Test
    fun `existing legacy auth fallback survives opaque exchange failure`() {
        coEvery { EaAuthManager.opaqueLaunchToken(any()) } throws IllegalStateException("exchange failed")
        coEvery { EaAuthManager.accessToken(any()) } returns "fallback-token"
        assertEquals(
            "<LSX><Response id=\"11\" sender=\"Utility\"><AuthToken value=\"fallback-token\"/></Response></LSX>",
            request("<LSX><Request recipient=\"Utility\" id=\"11\"><GetAuthToken/></Request></LSX>"),
        )
    }
}
