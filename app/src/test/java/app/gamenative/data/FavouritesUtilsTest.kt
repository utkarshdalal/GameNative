package app.gamenative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesUtilsTest {

    private data class Game(val appId: String, val name: String)

    @Test
    fun apply_addsAppIdWhenFavouriteIsTrue() {
        val result = FavouritesUtils.apply(setOf("a"), "b", favourite = true)

        assertEquals(setOf("a", "b"), result)
    }

    @Test
    fun apply_removesAppIdWhenFavouriteIsFalse() {
        val result = FavouritesUtils.apply(setOf("a", "b"), "b", favourite = false)

        assertEquals(setOf("a"), result)
    }

    @Test
    fun apply_isIdempotentWhenAlreadyInDesiredState() {
        val current = setOf("a")

        assertEquals(current, FavouritesUtils.apply(current, "a", favourite = true))
        assertEquals(current, FavouritesUtils.apply(current, "b", favourite = false))
    }

    @Test
    fun toggle_addsWhenMissingAndRemovesWhenPresent() {
        val added = FavouritesUtils.toggle(setOf("a"), "b")
        assertEquals(setOf("a", "b"), added)

        val removed = FavouritesUtils.toggle(added, "b")
        assertEquals(setOf("a"), removed)
    }

    @Test
    fun filter_keepsOnlyFavouritesAndPreservesOrder() {
        val games = listOf(
            Game(appId = "1", name = "First"),
            Game(appId = "2", name = "Second"),
            Game(appId = "3", name = "Third"),
        )

        val result = FavouritesUtils.filter(games, favourites = setOf("3", "1")) { it.appId }

        assertEquals(listOf("First", "Third"), result.map { it.name })
    }

    @Test
    fun filter_returnsEmptyWhenNothingIsFavourited() {
        val games = listOf(Game(appId = "1", name = "First"))

        val result = FavouritesUtils.filter(games, favourites = emptySet()) { it.appId }

        assertTrue(result.isEmpty())
    }

    @Test
    fun count_matchesTheNumberOfFavouritedItems() {
        val games = listOf(
            Game(appId = "1", name = "First"),
            Game(appId = "2", name = "Second"),
            Game(appId = "3", name = "Third"),
        )

        val count = FavouritesUtils.count(games, favourites = setOf("1", "3", "missing")) { it.appId }

        assertEquals(2, count)
    }

    @Test
    fun count_ignoresFavouriteIdsThatAreNotInTheList() {
        val games = listOf(Game(appId = "1", name = "First"))

        val count = FavouritesUtils.count(games, favourites = setOf("99")) { it.appId }

        assertEquals(0, count)
        assertFalse(count == games.size)
    }
}
