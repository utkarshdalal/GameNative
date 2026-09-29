package app.gamenative.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GOGGameTest {

/** Verifies the behavior described by this test: hidden Is The Logical Or Of Both Source Flags. */
    @Test
    fun hiddenIsTheLogicalOrOfBothSourceFlags() {
        val cases = listOf(
            false to false,
            true to false,
            false to true,
            true to true,
        )

        cases.forEach { (gogComHidden, galaxyHidden) ->
            val game = GOGGame(
                id = "$gogComHidden-$galaxyHidden",
                gogComHidden = gogComHidden,
                galaxyHidden = galaxyHidden,
            )

            assertEquals(gogComHidden || galaxyHidden, game.hidden)
        }
    }
}
