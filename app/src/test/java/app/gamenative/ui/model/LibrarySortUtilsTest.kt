package app.gamenative.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortUtilsTest {

    @Test fun everyCategoryAndInstalledGroupIsAlphabetical() {
        data class Game(val name: String, val installed: Boolean, val verdict: app.gamenative.data.CommunityCompatibilityVerdict?)
        val categories = app.gamenative.data.CommunityCompatibilityVerdict.entries.sortedBy { it.sortPriority }
        val games = categories.flatMap { listOf(Game("z $it", false, it), Game("a $it", false, it)) } +
            listOf(
                Game("z installed", true, categories.first()), Game("a installed", true, categories.last()),
                Game("Unavailable", false, null),
            )
        val sorted = games.reversed().sortedWith(
            LibrarySortUtils.compatibilityComparator(
                name = Game::name,
                isInstalled = Game::installed,
                summary = { game ->
                    game.verdict?.let {
                        app.gamenative.data.CommunityCompatibilitySummary(
                            it, app.gamenative.data.CommunityEvidenceTier.SAME_GPU, verdictLoaded = true,
                            sessionCount = if (game.name.startsWith("z")) 1000 else 1,
                        )
                    }
                },
            ),
        )
        assertEquals(
            listOf("a installed", "z installed") + categories.flatMap { listOf("a $it", "z $it") } + "Unavailable",
            sorted.map { it.name },
        )
    }

    private data class Entry(
        val name: String,
        val isInstalled: Boolean,
        val lastPlayed: Long,
    )

    @Test
    fun recentlyPlayedComparator_keepsInstalledFirstAndSortsEachGroupByLastPlayed() {
        val entries = listOf(
            Entry(name = "Uninstalled Recent", isInstalled = false, lastPlayed = 9000L),
            Entry(name = "Installed Older", isInstalled = true, lastPlayed = 1000L),
            Entry(name = "Installed Recent", isInstalled = true, lastPlayed = 5000L),
            Entry(name = "Uninstalled Older", isInstalled = false, lastPlayed = 3000L),
        )

        val sorted = entries.sortedWith(
            LibrarySortUtils.recentlyPlayedComparator(
                name = Entry::name,
                isInstalled = Entry::isInstalled,
                lastPlayed = Entry::lastPlayed,
            ),
        )

        assertEquals(
            listOf("Installed Recent", "Installed Older", "Uninstalled Recent", "Uninstalled Older"),
            sorted.map { it.name },
        )
    }

    @Test
    fun recentlyPlayedComparator_usesNameFallbackWhenLastPlayedMatches() {
        val entries = listOf(
            Entry(name = "Beta", isInstalled = false, lastPlayed = 0L),
            Entry(name = "alpha", isInstalled = false, lastPlayed = 0L),
            Entry(name = "Charlie", isInstalled = false, lastPlayed = 0L),
        )

        val sorted = entries.sortedWith(
            LibrarySortUtils.recentlyPlayedComparator(
                name = Entry::name,
                isInstalled = Entry::isInstalled,
                lastPlayed = Entry::lastPlayed,
            ),
        )

        assertEquals(listOf("alpha", "Beta", "Charlie"), sorted.map { it.name })
    }
}
