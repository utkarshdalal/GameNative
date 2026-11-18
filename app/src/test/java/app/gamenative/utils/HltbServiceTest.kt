package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HltbServiceTest {

    @Test
    fun formatHours_formatsExpectedCases() {
        listOf(
            0L to "--",
            -3600L to "--",
            3600L to "1.0",
            5400L to "1.5",
            3700L to "1.0",
            360_000L to "100.0",
        ).forEach { (seconds, expected) ->
            assertEquals(expected, HltbService.formatHours(seconds))
        }
    }

    @Test
    fun normalize_normalizesExpectedCases() {
        listOf(
            "HALO" to "halo",
            "The Witcher 3" to "the witcher 3",
            "A   B   C" to "a b c",
            "Hollow Knight!" to "hollow knight",
            "  Celeste  " to "celeste",
        ).forEach { (input, expected) ->
            assertEquals(expected, HltbService.normalize(input))
        }
    }

    @Test
    fun levenshtein_handlesCommonCases() {
        listOf(
            Triple("halo", "halo", 0),
            Triple("", "halo", 4),
            Triple("halo", "", 4),
            Triple("halo", "hale", 1),
            Triple("halo", "halos", 1),
            Triple("halos", "halo", 1),
            Triple("halo", "doom", 4),
        ).forEach { (left, right, expected) ->
            assertEquals(expected, HltbService.levenshtein(left, right))
        }
    }

    @Test
    fun levenshtein_isSymmetric() {
        val left = "witcher"
        val right = "alchemy"
        assertEquals(HltbService.levenshtein(left, right), HltbService.levenshtein(right, left))
    }

    @Test
    fun normalizedEquivalentTitlesHaveZeroDistance() {
        val left = HltbService.normalize("The Witcher 3: Wild Hunt")
        val right = HltbService.normalize("The Witcher 3: Wild Hunt!")
        assertEquals(0, HltbService.levenshtein(left, right))
    }
}
