package app.gamenative.store

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.gamenative.R
import app.gamenative.data.GameSource
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorePageLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `native success does not open browser`() {
        val intents = mutableListOf<Intent>()

        val result = StorePageLauncher.launch(context, steamTarget()) { intents += it }

        assertEquals(StorePageLaunchResult.NativeLaunched, result)
        assertEquals(1, intents.size)
        assertEquals("steam", intents.single().data?.scheme)
        assertEquals("com.valvesoftware.android.steam.community", intents.single().`package`)
    }

    @Test
    fun `native failures fall back to browser`() {
        val intents = mutableListOf<Intent>()

        val result = StorePageLauncher.launch(context, steamTarget()) { intent ->
            intents += intent
            if (intent.data?.scheme == "steam") throw ActivityNotFoundException()
        }

        assertEquals(StorePageLaunchResult.WebLaunched, result)
        assertEquals(3, intents.size)
        assertEquals("https", intents.last().data?.scheme)
        assertNull(intents.last().`package`)
    }

    @Test
    fun `second native candidate is used when first is unavailable`() {
        val intents = mutableListOf<Intent>()

        val result = StorePageLauncher.launch(context, steamTarget()) { intent ->
            intents += intent
            if (intent.dataString == "steam://store/400") throw ActivityNotFoundException()
        }

        assertEquals(StorePageLaunchResult.NativeLaunched, result)
        assertEquals(2, intents.size)
        assertEquals("steam://openurl/https://store.steampowered.com/app/400/", intents.last().dataString)
    }

    @Test
    fun `native security rejection still falls back to browser`() {
        val intents = mutableListOf<Intent>()

        val result = StorePageLauncher.launch(context, steamTarget()) { intent ->
            intents += intent
            if (intent.data?.scheme == "steam") throw SecurityException()
        }

        assertEquals(StorePageLaunchResult.WebLaunched, result)
        assertEquals("https", intents.last().data?.scheme)
    }

    @Test
    fun `browser security rejection returns copyable canonical url`() {
        val target = steamTarget()

        val result = StorePageLauncher.launch(context, target) { intent ->
            if (intent.data?.scheme == "steam") {
                throw ActivityNotFoundException()
            }
            throw SecurityException()
        }

        assertTrue(result is StorePageLaunchResult.Failed)
        assertEquals(
            target.canonicalWebUrl,
            (result as StorePageLaunchResult.Failed).canonicalWebUrl,
        )
    }

    @Test
    fun `web only target skips native route`() {
        val intents = mutableListOf<Intent>()
        val target = StorePageTarget.WebOnly(
            source = GameSource.GOG,
            canonicalWebUrl = "https://www.gog.com/en/game/baldurs_gate_iii",
            storeName = "GOG",
        )

        val result = StorePageLauncher.launch(context, target) { intents += it }

        assertEquals(StorePageLaunchResult.WebLaunched, result)
        assertEquals(1, intents.size)
        assertEquals("https", intents.single().data?.scheme)
    }

    @Test
    fun `localized label keeps store brand unchanged`() {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        }
        val localizedContext = context.createConfigurationContext(configuration)

        assertEquals(
            "Ver en Steam",
            localizedContext.getString(R.string.view_on_store, "Steam"),
        )
    }

    @Test
    fun `total failure returns copyable canonical url`() {
        val target = steamTarget()

        val result = StorePageLauncher.launch(context, target) {
            throw ActivityNotFoundException()
        }

        assertTrue(result is StorePageLaunchResult.Failed)
        assertEquals(
            target.canonicalWebUrl,
            (result as StorePageLaunchResult.Failed).canonicalWebUrl,
        )
    }

    private fun steamTarget(): StorePageTarget.NativeWithWebFallback =
        StorePageResolver.steam(400) as StorePageTarget.NativeWithWebFallback
}
