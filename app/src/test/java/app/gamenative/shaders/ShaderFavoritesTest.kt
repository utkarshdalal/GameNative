package app.gamenative.shaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Favorite presets (spec 2026-08-12, M3): user-pinned candidates for a shader
 * experiment session. Newest first, deduped, capped at [ShaderFavorites.MAX];
 * independent from ShaderRecents.
 */
class ShaderFavoritesTest {

    private class InMemoryStore : ShaderFavoritesStore {
        var data: List<String> = emptyList()
        override fun read(): List<String> = data
        override fun write(paths: List<String>) {
            data = paths
        }
    }

    private fun favorites(store: InMemoryStore = InMemoryStore()) = ShaderFavorites(store)

    // ── add / order / dedupe ──

    @Test
    fun `add puts the newest favorite first`() {
        val f = favorites()
        f.add("crt/crt-royale.slangp")
        f.add("film/technicolor.slangp")
        assertEquals(listOf("film/technicolor.slangp", "crt/crt-royale.slangp"), f.list())
    }

    @Test
    fun `adding an existing favorite moves it to the front without duplicating`() {
        val f = favorites()
        f.add("crt/crt-royale.slangp")
        f.add("film/technicolor.slangp")
        f.add("crt/crt-royale.slangp")
        assertEquals(listOf("crt/crt-royale.slangp", "film/technicolor.slangp"), f.list())
    }

    @Test
    fun `blank paths are ignored`() {
        val f = favorites()
        f.add("")
        f.add("   ")
        assertTrue(f.list().isEmpty())
    }

    @Test
    fun `favorites are capped at MAX 20 newest first`() {
        val f = favorites()
        repeat(25) { f.add("preset-$it.slangp") }
        val list = f.list()
        assertEquals(ShaderFavorites.MAX, list.size)
        assertEquals("preset-24.slangp", list.first())
        assertEquals("preset-5.slangp", list.last())
        assertFalse("preset-4.slangp" in list)
    }

    // ── remove / isFavorite / toggle ──

    @Test
    fun `remove deletes only the target favorite`() {
        val f = favorites()
        f.add("crt/crt-royale.slangp")
        f.add("film/technicolor.slangp")
        f.remove("crt/crt-royale.slangp")
        assertEquals(listOf("film/technicolor.slangp"), f.list())
    }

    @Test
    fun `isFavorite reflects the current state`() {
        val f = favorites()
        assertFalse(f.isFavorite("crt/crt-royale.slangp"))
        f.add("crt/crt-royale.slangp")
        assertTrue(f.isFavorite("crt/crt-royale.slangp"))
        f.remove("crt/crt-royale.slangp")
        assertFalse(f.isFavorite("crt/crt-royale.slangp"))
    }

    @Test
    fun `toggle adds when absent and removes when present`() {
        val f = favorites()
        assertTrue(f.toggle("crt/crt-royale.slangp"))
        assertTrue(f.isFavorite("crt/crt-royale.slangp"))
        assertFalse(f.toggle("crt/crt-royale.slangp"))
        assertFalse(f.isFavorite("crt/crt-royale.slangp"))
    }

    @Test
    fun `list is a snapshot copy - later writes do not alias`() {
        val store = InMemoryStore()
        val f = favorites(store)
        f.add("crt/crt-royale.slangp")
        val snapshot = f.list()
        f.add("film/technicolor.slangp")
        assertEquals(listOf("crt/crt-royale.slangp"), snapshot)
        assertEquals(2, f.list().size)
    }
}
