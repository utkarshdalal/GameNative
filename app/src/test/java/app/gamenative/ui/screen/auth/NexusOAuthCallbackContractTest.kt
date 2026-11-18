package app.gamenative.ui.screen.auth

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NexusOAuthCallbackContractTest {
    @Test
    fun `accepts exact registered redirect with OAuth response parameters`() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("app.gamenative://oauth/callback?code=authorization-code&state=random-state"),
        )

        assertTrue(NexusOAuthCallbackContract.matches(intent))
    }

    @Test
    fun `accepts exact registered redirect with OAuth error parameters`() {
        val uri = Uri.parse("app.gamenative://oauth/callback?error=access_denied&state=random-state")

        assertTrue(NexusOAuthCallbackContract.matches(uri))
    }

    @Test
    fun `consume returns valid callback then scrubs all sensitive intent data`() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("app.gamenative://oauth/callback?code=secret-code&state=secret-state"),
        ).apply {
            putExtra("secret", "value")
            clipData = ClipData.newPlainText("secret", "value")
            selector = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.test/secret"))
        }

        val callback = NexusOAuthCallbackContract.consumeAndScrub(intent)

        assertEquals("secret-code", callback?.getQueryParameter("code"))
        assertEquals("secret-state", callback?.getQueryParameter("state"))
        assertNull(intent.data)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertNull(intent.selector)
    }

    @Test
    fun `rejects explicit launches and look-alike redirects`() {
        val lookAlikes = listOf(
            Intent(Intent.ACTION_MAIN, Uri.parse("app.gamenative://oauth/callback?code=code&state=state")),
            Intent(Intent.ACTION_VIEW, Uri.parse("app.gamenative://oauth/callback/extra?code=code&state=state")),
            Intent(Intent.ACTION_VIEW, Uri.parse("app.gamenative://oauth.evil/callback?code=code&state=state")),
            Intent(Intent.ACTION_VIEW, Uri.parse("app.gamenative://oauth@evil/callback?code=code&state=state")),
            Intent(Intent.ACTION_VIEW, Uri.parse("app.gamenative://oauth:443/callback?code=code&state=state")),
            Intent(Intent.ACTION_VIEW, Uri.parse("app.gamenative://oauth/callback#code=fragment")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://oauth/callback?code=code&state=state")),
        )

        lookAlikes.forEach { assertFalse(it.dataString, NexusOAuthCallbackContract.matches(it)) }
    }
}
