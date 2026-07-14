package app.gamenative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesUtilsTest {

    private data class Game(val appId: String, val name: String)

    @Test
    fun apply_addsAppIdWhenFavoriteIsTrue() {
        val result = FavoritesUtils.apply(setOf("a"), "b", favorite = true)

        assertEquals(setOf("a", "b"), result)
    }

    @Test
    fun apply_removesAppIdWhenFavoriteIsFalse() {
        val result = FavoritesUtils.apply(setOf("a", "b"), "b", favorite = false)

        assertEquals(setOf("a"), result)
    }

    @Test
    fun apply_isIdempotentWhenAlreadyInDesiredState() {
        val current = setOf("a")

        assertEquals(current, FavoritesUtils.apply(current, "a", favorite = true))
        assertEquals(current, FavoritesUtils.apply(current, "b", favorite = false))
    }

    @Test
    fun filter_keepsOnlyFavoritesAndPreservesOrder() {
        val games = listOf(
            Game(appId = "1", name = "First"),
            Game(appId = "2", name = "Second"),
            Game(appId = "3", name = "Third"),
        )

        val result = FavoritesUtils.filter(games, favorites = setOf("3", "1")) { it.appId }

        assertEquals(listOf("First", "Third"), result.map { it.name })
    }

    @Test
    fun filter_returnsEmptyWhenNothingIsFavorited() {
        val games = listOf(Game(appId = "1", name = "First"))

        val result = FavoritesUtils.filter(games, favorites = emptySet()) { it.appId }

        assertTrue(result.isEmpty())
    }

    @Test
    fun count_matchesTheNumberOfFavoritedItems() {
        val games = listOf(
            Game(appId = "1", name = "First"),
            Game(appId = "2", name = "Second"),
            Game(appId = "3", name = "Third"),
        )

        val count = FavoritesUtils.count(games, favorites = setOf("1", "3", "missing")) { it.appId }

        assertEquals(2, count)
    }

    @Test
    fun count_ignoresFavoriteIdsThatAreNotInTheList() {
        val games = listOf(Game(appId = "1", name = "First"))

        val count = FavoritesUtils.count(games, favorites = setOf("99")) { it.appId }

        assertEquals(0, count)
        assertFalse(count == games.size)
    }
}
