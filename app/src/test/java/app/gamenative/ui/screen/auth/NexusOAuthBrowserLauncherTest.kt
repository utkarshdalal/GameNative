package app.gamenative.ui.screen.auth

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NexusOAuthBrowserLauncherTest {
    @Test
    fun `allows only the Nexus authorization endpoint`() {
        assertTrue(
            NexusOAuthBrowserLauncher.isAllowedAuthorizationUri(
                Uri.parse("https://users.nexusmods.com/oauth/authorize?client_id=gamenative"),
            ),
        )

        listOf(
            "http://users.nexusmods.com/oauth/authorize?client_id=gamenative",
            "https://users.nexusmods.com.evil/oauth/authorize?client_id=gamenative",
            "https://users.nexusmods.com/oauth/token",
            "https://user@users.nexusmods.com/oauth/authorize",
            "https://users.nexusmods.com:444/oauth/authorize",
            "https://users.nexusmods.com/oauth/authorize#fragment",
        ).forEach { value ->
            assertFalse(value, NexusOAuthBrowserLauncher.isAllowedAuthorizationUri(Uri.parse(value)))
        }
    }
}
