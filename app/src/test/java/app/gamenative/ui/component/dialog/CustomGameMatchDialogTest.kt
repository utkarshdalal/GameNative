package app.gamenative.ui.component.dialog

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import app.gamenative.api.CommunityGame
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class CustomGameMatchDialogTest {
    @get:Rule val compose = createComposeRule()

    private val hollowKnight = CommunityGame(11, "Hollow Knight")
    private val silksong = CommunityGame(19, "Hollow Knight: Silksong")

    private val queries = mutableListOf<String>()
    private var matched: CommunityGame? = null
    private var removed = 0

    private fun render(
        folderName: String = "Hollow.Knight.v1.5.78 [GOG]",
        matchedName: String? = null,
        search: suspend (String) -> List<CommunityGame> = { listOf(hollowKnight, silksong) },
    ) {
        compose.setContent {
            CustomGameMatchDialog(
                folderName = folderName,
                matchedName = matchedName,
                onMatch = { matched = it },
                onRemoveMatch = { removed++ },
                onDismissRequest = {},
                searchGames = { query ->
                    queries += query
                    search(query)
                },
            )
        }
    }

    @Test
    fun opening_searchesCleanedFolderNameAndPickingResultMatchesIt() {
        render()

        compose.onNodeWithText("Hollow Knight: Silksong").assertIsDisplayed().performClick()

        assertEquals(listOf("Hollow Knight"), queries)
        assertEquals(silksong, matched)
        compose.onNodeWithText("Remove match").assertDoesNotExist()
    }

    @Test
    fun editingQuery_searchesOnlyOnSubmit() {
        render()
        compose.onNodeWithText("Hollow Knight: Silksong").assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextReplacement("Silksong")
        compose.waitForIdle()
        assertEquals(listOf("Hollow Knight"), queries)

        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("Hollow Knight", "Silksong"), queries)

        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Hollow Knight", "Silksong", "Silksong"), queries)
    }

    @Test
    fun existingMatch_searchesMatchedNameAndCanBeRemoved() {
        render(folderName = "hk_gog", matchedName = silksong.name)

        compose.onNodeWithText("Matched to Hollow Knight: Silksong").assertIsDisplayed()
        compose.onNodeWithText("Remove match").performClick()

        assertEquals(listOf("Hollow Knight: Silksong"), queries)
        assertEquals(1, removed)
    }

    @Test
    fun failedSearch_showsErrorAndRetrySearchesAgain() {
        var calls = 0
        render(search = {
            if (calls++ == 0) throw IOException("offline")
            listOf(silksong)
        })

        compose.onNodeWithText("Search failed: offline").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        compose.onNodeWithText("Hollow Knight: Silksong").assertIsDisplayed()
        assertEquals(2, queries.size)
    }

    @Test
    fun noResults_saysSo() {
        render(search = { emptyList() })

        compose.onNodeWithText("No games found. Try a shorter name.").assertIsDisplayed()
    }

    @Test
    fun suggestedMatchQuery_stripsTagsVersionsAndSeparators() {
        assertEquals("Hollow Knight", suggestedMatchQuery("Hollow Knight"))
        assertEquals("Hollow Knight", suggestedMatchQuery("Hollow Knight v1.5.78"))
        assertEquals("Hollow Knight", suggestedMatchQuery("Hollow.Knight.v1.5.78.11833"))
        assertEquals("Elden Ring", suggestedMatchQuery("Elden Ring [FitGirl Repack]"))
        assertEquals("Hades", suggestedMatchQuery("Hades (GOG) {v2}"))
        assertEquals("Left 4 Dead 2", suggestedMatchQuery("Left_4_Dead_2"))
        assertEquals("Fallout 3 GOTY", suggestedMatchQuery("Fallout 3 GOTY build 1234"))
        assertEquals("Stardew Valley", suggestedMatchQuery("Stardew Valley 1.6.8"))
    }

    @Test
    fun suggestedMatchQuery_splitsDottedReleaseNames() {
        assertEquals("Left 4 Dead 2", suggestedMatchQuery("Left.4.Dead.2"))
        assertEquals("Grand Theft Auto V", suggestedMatchQuery("Grand.Theft.Auto.V"))
        assertEquals("The Witcher 3 Wild Hunt", suggestedMatchQuery("The.Witcher.3.Wild.Hunt"))
        assertEquals("A Plague Tale Innocence", suggestedMatchQuery("A.Plague.Tale.Innocence"))
        assertEquals("Baldurs Gate 3", suggestedMatchQuery("Baldurs.Gate.3.v4.1.1.3624901"))
        assertEquals("Game Deluxe", suggestedMatchQuery("Game.v1.2.3.Deluxe"))
        assertEquals("Max Payne 3", suggestedMatchQuery("Max.Payne.3-RELOADED"))
        assertEquals("Celeste", suggestedMatchQuery("Celeste.v1.4.0.0-GOG"))
        assertEquals("Hollow Knight", suggestedMatchQuery("Hollow.Knight.[GOG]"))
        assertEquals("Hades", suggestedMatchQuery("Hades.v1.38290.(GOG)"))
        assertEquals("Disco Elysium - The Final Cut", suggestedMatchQuery("Disco.Elysium.-.The.Final.Cut"))
    }

    @Test
    fun suggestedMatchQuery_keepsNumbersAndInitialismsThatArePartOfTheTitle() {
        assertEquals("Half-Life 2", suggestedMatchQuery("Half-Life 2"))
        assertEquals("7 Days to Die", suggestedMatchQuery("7 Days to Die"))
        assertEquals("Grand Theft Auto V", suggestedMatchQuery("Grand Theft Auto V"))
        assertEquals("S.T.A.L.K.E.R. Shadow of Chernobyl", suggestedMatchQuery("S.T.A.L.K.E.R. Shadow of Chernobyl"))
        assertEquals("F.E.A.R.", suggestedMatchQuery("F.E.A.R."))
        assertEquals("Dr. Langeskov", suggestedMatchQuery("Dr. Langeskov"))
        assertEquals("Plants vs. Zombies", suggestedMatchQuery("Plants vs. Zombies"))
        assertEquals("X-COM", suggestedMatchQuery("X-COM"))
        assertEquals("Danganronpa V3: Killing Harmony", suggestedMatchQuery("Danganronpa V3: Killing Harmony"))
        assertEquals(
            "KINGDOM HEARTS HD 2.8 Final Chapter Prologue",
            suggestedMatchQuery("KINGDOM HEARTS HD 2.8 Final Chapter Prologue"),
        )
    }

    @Test
    fun suggestedMatchQuery_fallsBackToFolderNameWhenNothingIsLeft() {
        assertEquals("[v1.0]", suggestedMatchQuery(" [v1.0] "))
    }
}
