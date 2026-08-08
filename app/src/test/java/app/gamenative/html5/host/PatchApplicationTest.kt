package app.gamenative.html5.host

import android.webkit.WebResourceResponse
import app.gamenative.html5.profile.Patch
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

// Robolectric needed so WebResourceResponse.getMimeType() isn't a stub throw.
@RunWith(RobolectricTestRunner::class)
class PatchApplicationTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @After fun cleanup() { unmockkAll() }

    private fun mockResponse(mime: String, bytes: ByteArray): WebResourceResponse {
        val resp = mockk<WebResourceResponse>(relaxed = true)
        every { resp.mimeType } returns mime
        every { resp.encoding } returns "utf-8"
        every { resp.data } returns ByteArrayInputStream(bytes)
        return resp
    }

    @Test fun applyUrlRedirects_rewrites_exact_match() {
        val patches = listOf(Patch.UrlPathRedirect("/bgm.mp3", "/audio/bgm/title.ogg"))
        assertEquals("/audio/bgm/title.ogg", PatchApplication.applyUrlRedirects("/bgm.mp3", patches))
    }

    @Test fun applyUrlRedirects_returns_null_for_unknown_path() {
        val patches = listOf(Patch.UrlPathRedirect("/bgm.mp3", "/x"))
        assertNull(PatchApplication.applyUrlRedirects("/other.mp3", patches))
    }

    @Test fun applyUrlRedirects_first_match_wins() {
        val patches = listOf(
            Patch.UrlPathRedirect("/a", "/b"),
            Patch.UrlPathRedirect("/a", "/c"),
        )
        assertEquals("/b", PatchApplication.applyUrlRedirects("/a", patches))
    }

    @Test fun applyServeTime_applies_response_body_replace() {
        val patches = listOf(
            Patch.ResponseBodyReplace(
                pathPattern = "main\\.js$",
                find = "\"exportType\":\"nwjs\"",
                replace = "\"exportType\":\"html5\"",
            ),
        )
        val body = "var cfg = {\"exportType\":\"nwjs\", \"other\":true};".toByteArray()
        val orig = mockResponse("application/javascript", body)
        val result = PatchApplication.applyServeTime(orig, "/scripts/main.js", patches, null)
        val resultText = String(result.data.readBytes())
        assertTrue("replaced body should contain html5", resultText.contains("\"exportType\":\"html5\""))
        assertTrue("replaced body should NOT contain nwjs", !resultText.contains("\"exportType\":\"nwjs\""))
    }

    @Test fun applyServeTime_invokes_decryptContext_for_rpgmv_paths() {
        val ctx = mockk<Html5DecryptContext>(relaxed = true)
        every { ctx.wrapStream(any()) } returns ByteArrayInputStream("decrypted".toByteArray())
        val patches = listOf(Patch.AssetDecrypt("rpgmv-xor"))
        val orig = mockResponse("application/octet-stream", "encrypted-bytes".toByteArray())
        PatchApplication.applyServeTime(orig, "/img/pics/a.rpgmvp", patches, ctx)
        verify(atLeast = 1) { ctx.wrapStream(any()) }
    }

    @Test fun applyServeTime_audio_ext_remap_changes_mime() {
        val patches = listOf(Patch.AudioExtensionRemap(".rpgmvo", ".ogg"))
        val orig = mockResponse("application/octet-stream", ByteArray(0))
        val result = PatchApplication.applyServeTime(orig, "/audio/bgm/title.rpgmvo", patches, null)
        assertEquals("audio/ogg", result.mimeType)
    }

    @Test fun patch_throws_returns_original_response_and_logs() {
        // invalid regex in pathPattern — regex compile throws PatternSyntaxException
        val patches = listOf(Patch.ResponseBodyReplace("[invalid(", "a", "b"))
        val orig = mockResponse("text/plain", "test".toByteArray())
        val result = PatchApplication.applyServeTime(orig, "/x", patches, null)
        assertEquals("test", String(result.data.readBytes()))
    }

    @Test fun applyServeTime_order_is_audio_then_decrypt_then_replace() {
        // AudioExtensionRemap targets .rpgmvo — does NOT apply to .rpgmvp path.
        // AssetDecrypt wraps stream → mime becomes image/png for .rpgmvp.
        // ResponseBodyReplace is a no-op (find string absent). mime stays image/png.
        val ctx = mockk<Html5DecryptContext>(relaxed = true)
        every { ctx.wrapStream(any()) } answers { firstArg() }
        val patches = listOf(
            Patch.ResponseBodyReplace("\\.rpgmvp$", "zzz", "zzz"),
            Patch.AssetDecrypt("rpgmv-xor"),
            Patch.AudioExtensionRemap(".rpgmvo", ".ogg"),
        )
        val orig = mockResponse("application/octet-stream", "content".toByteArray())
        val result = PatchApplication.applyServeTime(orig, "/img/pics/a.rpgmvp", patches, ctx)
        assertEquals("image/png", result.mimeType)
    }
}
