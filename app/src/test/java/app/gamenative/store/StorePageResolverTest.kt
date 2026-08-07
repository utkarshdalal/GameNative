package app.gamenative.store

import app.gamenative.data.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorePageResolverTest {
    @Test
    fun `steam target contains native candidates and canonical fallback`() {
        val target = StorePageResolver.steam(400) as StorePageTarget.NativeWithWebFallback

        assertEquals(GameSource.STEAM, target.source)
        assertEquals("Steam", target.storeName)
        assertEquals("https://store.steampowered.com/app/400/", target.canonicalWebUrl)
        assertEquals("steam://store/400", target.nativeCandidates.first().uri)
        assertTrue(target.nativeCandidates.all { it.packageName == "com.valvesoftware.android.steam.community" })
    }

    @Test
    fun `steam rejects invalid app id`() {
        assertNull(StorePageResolver.steam(0))
        assertNull(StorePageResolver.steam(-1))
    }

    @Test
    fun `gog target uses validated slug`() {
        val target = StorePageResolver.gog("baldurs_gate_iii") as StorePageTarget.WebOnly

        assertEquals("GOG", target.storeName)
        assertEquals("https://www.gog.com/en/game/baldurs_gate_iii", target.canonicalWebUrl)
    }

    @Test
    fun `gog rejects unsafe slug`() {
        assertNull(StorePageResolver.gog("../account"))
        assertNull(StorePageResolver.gog("game name"))
    }

    @Test
    fun `epic target uses explicit validated slug`() {
        val target = StorePageResolver.epic("sol-cesto-e9b803") as StorePageTarget.WebOnly

        assertEquals("Epic", target.storeName)
        assertEquals("https://store.epicgames.com/p/sol-cesto-e9b803", target.canonicalWebUrl)
    }

    @Test
    fun `epic rejects unsafe slug`() {
        assertNull(StorePageResolver.epic("https://example.com"))
        assertNull(StorePageResolver.epic("game name"))
    }
}
